/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import io.izzel.arclight.common.prts.kernel.auth.HolderKind;
import io.izzel.arclight.common.prts.kernel.auth.WriteLevel;
import io.izzel.arclight.common.prts.kernel.auth.WriteVersionSlots;
import io.izzel.arclight.common.prts.kernel.jobs.JobDeclaration;
import io.izzel.arclight.common.prts.kernel.jobs.JobGraph;
import io.izzel.arclight.common.prts.kernel.jobs.PipelineBatchLedger;
import io.izzel.arclight.common.prts.kernel.sites.WriteClaimTable;
import io.izzel.arclight.common.prts.kernel.sites.WorldWriteGuard;
import io.izzel.arclight.common.prts.kernel.sites.WriteControlledSlots;
import io.izzel.arclight.common.prts.kernel.sites.WorldEpochs;
import io.izzel.arclight.common.prts.support.PrtsPipelineRows;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/** Turns the write set a frozen job declared into the claim a physical write arrives against.
 *
 * <p>The plan declares a write set per job; the claim table translates that declared set into the
 * write path keys the domain writes through, and this point seats one slot for the span in which the
 * declaring job is the writer. A job is the writer while the thread the writes are judged on is
 * inside one of its own work items: the window is opened by the mailbox seam and closed by it again,
 * so a write made while no job of the domain runs is not a domain write and keeps the default path.
 *
 * <p>Nothing here decides a write: a write the seated slot covers is handed to the same judged path
 * the short path always had, and only a claim whose contract still holds is captured. The writes a
 * claim landed are reported once, at the freeze that closes the span, as the batches a real commit
 * log ordered - a span that landed nothing reports nothing.
 */
public final class PipelineWriteClaims implements PrtsPipelineRows.MailboxOwnerTap {

    /** How many ticks a claim of one declared job covers; the declaration names the window and this
     * point never extends it. */
    public static final int HOLD_TICKS = 40;

    /** One declared job that claims writes: the batch the plan ordered, the write set the claim table
     * translated out of its declaration and the contract a slot carries. */
    public record Claim(String nodeKey, String worldId, String mailbox, String domainId,
                        long planSequence, int position, long expectedVersion, long worldEpoch,
                        long expireTick, HolderKind holderKind, String holderSiteId,
                        Set<String> writeSet, Object levelRef,
                        WriteControlledSlots.WriteSpace space) {
    }

    /** One batch a claim landed, named the way the commit log identifies a batch, with the plan, seat
     * and lifecycle that authorized it. It is reported for a span that actually landed writes and for a
     * node the plan still carries. */
    public record Report(String worldId, String domainId, String nodeKey, long planSequence,
                         int position, long lifecycle, long expectedVersion, long writeSetDigest,
                         int rows) {
    }

    /** One cell a reported batch landed writes for, with the task ordinal its window carried and
     * the identity of every write behind those rows. A landing whose write named no identity leaves
     * this list short of the row count, so a settlement can only fail closed on it. */
    public record CellRows(long cell, long taskOrdinal, int rows, List<String> rowContent) {

        public CellRows {
            rowContent = rowContent == null ? List.of() : List.copyOf(rowContent);
        }
    }

    /** One batch report upgraded to its per-cell form. The plain report is carried unchanged; the
     * cells are the windows the writes were attributed to and the identities of their writes. A
     * batch with a landing whose window carried no task token keeps an empty cell list and
     * {@code attributed()} false, so a consumer of this payload can only fall back to the plain
     * path and never split a batch it cannot attribute. A batch with a landing whose caller named
     * no position keeps {@code rowsNamed()} false and the same fallback. */
    public record Attributed(Report report, List<CellRows> cells, boolean attributed,
                             boolean rowsNamed) {

        public Attributed {
            cells = cells == null ? List.of() : List.copyOf(cells);
        }
    }

    /** One open window: the slots it seated, the tick it was seated on and the token of the task it
     * covers. An unplaced mailbox seats every declared job of that mailbox, and the level decides. */
    private record Window(List<WriteControlledSlots.SlotKey> keys, long tickIndex, Object token) {
    }

    private static final class Landed {

        private final WriteControlledSlots.Slot slot;
        private final LongAdder rows = new LongAdder();
        private final Map<Long, CellCount> cells = new LinkedHashMap<>();
        private boolean unattributed;
        private boolean rowsNamed = true;

        private Landed(WriteControlledSlots.Slot slot) {
            this.slot = slot;
        }
    }

    /** The writes one task window landed for one cell; a second task of the same cell makes the
     * attribution ambiguous rather than naming one of them. */
    private static final class CellCount {

        private final long ordinal;
        private final LongAdder rows = new LongAdder();
        private final List<String> content = new ArrayList<>();

        private CellCount(long ordinal) {
            this.ordinal = ordinal;
        }
    }

    /** The plan a span was declared under: its sequence, the lifecycle that published it and the seat
     * every node of it holds. It is built when the plan is declared, so a landing is settled against
     * the plan that authorized it and not against the plan that froze after it. */
    private record Authorization(long planSequence, long lifecycle, List<Seat> seats) {
    }

    /** The attribution one node of the authorizing plan gave a write: its world and domain and the
     * position it holds in that plan's order. */
    private record Seat(String worldId, String domainId, String nodeKey, int position) {
    }

    /** The seat a landing belongs to: the plan, position and node key the slot was seated with. A
     * landing is never the landing of another plan that happens to reuse the key. */
    private record Landing(long planSequence, int position, String nodeKey) {
    }

    private final WriteVersionSlots versions;
    private final WorldEpochs epochs;
    private final BooleanSupplier judgeThread;
    private final Function<String, Object> levelOf;
    private final Map<Object, String> levelWorlds = new ConcurrentHashMap<>();
    private final Map<Thread, Window> windows = new ConcurrentHashMap<>();
    private final Map<Landing, Landed> landings = new ConcurrentHashMap<>();
    private volatile Map<String, List<Claim>> byMailbox = new ConcurrentHashMap<>();
    private volatile Authorization authorizing;
    private final Map<String, LongAdder> landedByNode = new ConcurrentHashMap<>();
    private final Consumer<Attributed> settlement;

    private volatile WriteControlledSlots table = WriteControlledSlots.NOTHING;
    private volatile Function<JobGraph.Node, WriteControlledSlots.WriteSpace> writeSpaces =
        node -> WriteControlledSlots.WriteSpace.NONE;
    private volatile Map<String, Claim> declarations = Map.of();
    /** The slots the newest declaration seated; the next freeze replaces them. */
    private volatile List<WriteControlledSlots.SlotKey> declaredSeats = List.of();
    private volatile String lastWindow = "";
    private volatile boolean enabled;
    private long tickIndex;
    private long generations;
    private long declared;
    private long seated;
    private long disarmed;
    private long swept;
    private long refused;
    private long claimedWindows;
    private long unclaimedWindows;
    private long unplacedWindows;
    private long offThreadWindows;
    private long overlappingWindows;
    private long mismatchedEnds;
    private long landedWrites;
    private long reportedBatches;
    private long landedWithoutNode;
    private long unclaimedDomain;
    private long foreignRefs;
    private long emptyWriteSet;
    private long noMailbox;
    private long noDemand;
    private long noWorld;
    private long noVersion;
    private long noEpoch;

    /** Creates the point. Nothing arms a claim until a table is wired in and the window is on. */
    public PipelineWriteClaims(WriteVersionSlots versions, WorldEpochs epochs,
                               BooleanSupplier judgeThread, Function<String, Object> levelOf) {
        this(versions, epochs, judgeThread, levelOf, null);
    }

    /** The same, with the sink one settlement period hands the per-cell form of every report to. A
     * null sink keeps the plain shape: the reports are built and consumed exactly as before, and
     * the attribution bookkeeping is not kept. */
    public PipelineWriteClaims(WriteVersionSlots versions, WorldEpochs epochs,
                               BooleanSupplier judgeThread, Function<String, Object> levelOf,
                               Consumer<Attributed> settlement) {
        this.versions = versions;
        this.epochs = epochs;
        this.judgeThread = judgeThread;
        this.levelOf = levelOf;
        this.settlement = settlement;
    }

    /** Names the claim table a seated slot is registered in. */
    public void table(WriteControlledSlots slots) {
        this.table = slots == null ? WriteControlledSlots.NOTHING : slots;
    }

    /** Names where the spatial range a declared job writes in is read from. The reader is pointed at
     * the range each frozen declaration carries: a declared range places the write set, and a
     * declaration that names none leaves it unplaceable, so no slot is registered for it. */
    public void writeSpaces(Function<JobGraph.Node, WriteControlledSlots.WriteSpace> reader) {
        this.writeSpaces = reader == null
            ? node -> WriteControlledSlots.WriteSpace.NONE : reader;
    }

    /** Decides whether a declared write set is turned into a claim at all. Off, no window seats or
     * retires anything and every write keeps the path it had. */
    public void refresh(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            retireDeclared();
            closeAll();
        }
    }

    public boolean enabled() {
        return enabled;
    }

    /** Whether a settlement sink is installed: with none the point keeps the plain report shape
     * and never builds the per-cell payload. */
    public boolean settlementArmed() {
        return settlement != null;
    }

    /** Takes the write sets the plan just froze and turns the claimed ones into the claims the next
     * span writes against, and saves the plan itself as the authorization of that span. A job the
     * plan carries no claim for declares nothing here. */
    public int declare(JobGraph graph, long planSequence, long tick) {
        return declare(graph, planSequence, tick, 0L);
    }

    /** The same, with the lifecycle the plan store published the graph in. A span declared before a
     * reset keeps the lifecycle it was declared under, so the next lifecycle cannot adopt it. */
    public int declare(JobGraph graph, long planSequence, long tick, long lifecycle) {
        Map<String, Claim> next = new LinkedHashMap<>();
        List<Seat> seats = new ArrayList<>();
        if (graph != null) {
            for (JobGraph.Node node : graph.nodes()) {
                Claim claim = claimOf(node, planSequence, tick);
                if (claim != null) {
                    next.put(node.key(), claim);
                }
                seats.add(new Seat(node.worldId(), node.domainId(), node.key(), node.position()));
            }
        }
        Map<String, List<Claim>> byMailboxNext = new ConcurrentHashMap<>();
        for (Claim claim : next.values()) {
            byMailboxNext.computeIfAbsent(claim.mailbox(), key -> new ArrayList<>()).add(claim);
        }
        declarations = Map.copyOf(next);
        byMailbox = byMailboxNext;
        authorizing = graph == null ? null
            : new Authorization(planSequence, lifecycle, List.copyOf(seats));
        declared += next.size();
        return next.size();
    }

    /** Reports the batches the span that closed landed, each against the seat the saved plan gave
     * its node. The plan read here is the one {@link #declare} saved, never the plan that just
     * froze; a landing the saved plan did not seat is counted as a landing without a node. */
    public int reportLanded(Consumer<Report> sink) {
        Authorization held = authorizing;
        if (held == null || sink == null) {
            return 0;
        }
        int reported = 0;
        for (Seat seat : held.seats()) {
            Landed landed = landings.remove(new Landing(held.planSequence(), seat.position(),
                seat.nodeKey()));
            long rows = landed == null ? 0L : landed.rows.sum();
            if (rows <= 0L) {
                continue;
            }
            WriteControlledSlots.Slot slot = landed.slot;
            Report report = new Report(seat.worldId(), seat.domainId(), seat.nodeKey(),
                held.planSequence(), seat.position(), held.lifecycle(), slot.expectedVersion(),
                WriteControlledSlots.foldValue(slot.writeSet()),
                (int) Math.min(Integer.MAX_VALUE, rows));
            sink.accept(report);
            Consumer<Attributed> armed = settlement;
            if (armed != null) {
                armed.accept(attributedOf(report, landed));
            }
            reported++;
        }
        for (Landing key : new ArrayList<>(landings.keySet())) {
            if (landings.remove(key) != null) {
                landedWithoutNode++;
            }
        }
        reportedBatches += reported;
        return reported;
    }

    /** The world one level reference belongs to, as the claim that seated the slot named it. */
    public String worldOf(Object levelRef) {
        String world = levelRef == null ? null : levelWorlds.get(levelRef);
        return world == null ? "" : world;
    }

    @Override
    public void ownerTask(String world, String mailbox) {
    }

    @Override
    public void ownerRound(String world, String mailbox) {
    }

    /** One work item of a mailbox begins without a token: the legacy face of the span. */
    @Override
    public void ownerTaskBegin(String world, String mailbox) {
        ownerTaskBegin(world, mailbox, null);
    }

    /** One work item of a mailbox begins: while it runs, the job that mailbox carried is the writer
     * of its world, and the claim of that job is seated for exactly that span. A thread that already
     * holds a window is refused rather than overwritten, so a nested body cannot inherit the outer
     * claim.
     *
     * <p>A token names a pipeline task body: the window is seated on the thread that runs the body. */
    @Override
    public void ownerTaskBegin(String world, String mailbox, Object token) {
        if (!enabled) {
            return;
        }
        if (token == null && judgeThread != null && !judgeThread.getAsBoolean()) {
            offThreadWindows++;
            return;
        }
        List<Claim> candidates = candidatesOf(world, mailbox);
        if (candidates.isEmpty()) {
            unclaimedWindows++;
            lastWindow = (world == null ? "" : world) + "|" + (mailbox == null ? "" : mailbox);
            return;
        }
        if (windows.containsKey(Thread.currentThread())) {
            overlappingWindows++;
            return;
        }
        List<WriteControlledSlots.SlotKey> keys = new ArrayList<>(candidates.size());
        for (Claim claim : candidates) {
            WriteControlledSlots.Slot slot = slotOf(claim, ++generations);
            if (table.register(slot) != WriteControlledSlots.Refusal.NONE) {
                refused++;
                continue;
            }
            keys.add(slot.key());
            seated++;
        }
        if (keys.isEmpty()) {
            return;
        }
        windows.put(Thread.currentThread(), new Window(List.copyOf(keys), tickIndex, token));
    }

    /** The claims one window seats. A mailbox the placement table placed on a world seats the claim
     * of that world alone; a mailbox it could not place seats the claim of every world whose plan
     * declared a job of that mailbox, and the level a write arrives on is what decides which of them
     * covers it. Nothing is claimed that no plan declared. */
    private List<Claim> candidatesOf(String world, String mailbox) {
        if (mailbox == null || mailbox.isEmpty()) {
            return List.of();
        }
        if (world != null && !world.isEmpty() && !PrtsPipelineRows.UNPLACED_WORLD.equals(world)) {
            List<Claim> claimOfWorld = byMailbox.get(mailbox);
            if (claimOfWorld == null) {
                return List.of();
            }
            for (Claim claim : claimOfWorld) {
                if (world.equals(claim.worldId())) {
                    return List.of(claim);
                }
            }
            return List.of();
        }
        List<Claim> candidates = byMailbox.get(mailbox);
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        unplacedWindows++;
        return candidates;
    }

    /** One work item ends without a token: the legacy face of the span. */
    @Override
    public void ownerTaskEnd(String world, String mailbox) {
        ownerTaskEnd(world, mailbox, null);
    }

    /** One work item ends: the claim is retired and its writes fall back to the default path. An end
     * whose token is not the open window's closes nothing, so the outer body keeps its claim. */
    @Override
    public void ownerTaskEnd(String world, String mailbox, Object token) {
        Thread thread = Thread.currentThread();
        Window open = windows.get(thread);
        if (open == null) {
            return;
        }
        if (token != null && open.token() != null && !token.equals(open.token())) {
            mismatchedEnds++;
            return;
        }
        if (!windows.remove(thread, open)) {
            return;
        }
        for (WriteControlledSlots.SlotKey key : open.keys()) {
            if (retireWindow(key)) {
                disarmed++;
            }
        }
    }

    /** Seats the write set the declaration carries, so a plan with no task body still covers its own
     * range until the next freeze; a failed registration leaves no slot at all (fail-closed). */
    public int seatDeclared() {
        if (!enabled) {
            return 0;
        }
        List<Claim> claimed = claims();
        retireDeclared();
        if (claimed.isEmpty()) {
            return 0;
        }
        List<WriteControlledSlots.SlotKey> keys = new ArrayList<>(claimed.size());
        for (Claim claim : claimed) {
            WriteControlledSlots.Slot slot = slotOf(claim, ++generations);
            if (table.register(slot) != WriteControlledSlots.Refusal.NONE) {
                for (WriteControlledSlots.SlotKey key : keys) {
                    table.retire(key);
                }
                return -1;
            }
            keys.add(slot.key());
        }
        declaredSeats = List.copyOf(keys);
        return keys.size();
    }

    private static WriteControlledSlots.Slot slotOf(Claim claim, long generation) {
        return new WriteControlledSlots.Slot(
            new WriteControlledSlots.SlotKey(claim.worldId(), claim.domainId(),
                WriteLevel.REGION, WriteControlledSlots.SlotKey.segmentOf(claim.space()),
                claim.planSequence()),
            claim.levelRef(), claim.nodeKey(), claim.position(), claim.expectedVersion(),
            generation, claim.worldEpoch(), claim.expireTick(), claim.holderKind(),
            claim.holderSiteId(), claim.writeSet(), claim.space());
    }

    /** One tick of the kernel: a window that was left open across the tick boundary is retired, so a
     * claim the writer never closed cannot cover the writes of a later tick. */
    public void noteTick(long tick) {
        tickIndex = tick;
        for (Map.Entry<Thread, Window> entry : windows.entrySet()) {
            Window open = entry.getValue();
            if (open.tickIndex() == tick || !windows.remove(entry.getKey(), open)) {
                continue;
            }
            for (WriteControlledSlots.SlotKey key : open.keys()) {
                if (retireWindow(key)) {
                    disarmed++;
                }
            }
            swept++;
        }
    }

    /** One write a claim landed without a row identity: the batch it belongs to is counted here and
     * reported at the freeze that closes the span, never during the write. */
    public void noteLanded(WriteControlledSlots.Slot slot, String worldId) {
        noteLanded(slot, worldId, null);
    }

    /** One write a claim landed with the identity the write path named for it: the batch it belongs
     * to is counted here and reported at the freeze that closes the span, never during the write. */
    public void noteLanded(WriteControlledSlots.Slot slot, String worldId,
                           WorldWriteGuard.LandedRow row) {
        if (slot == null) {
            return;
        }
        Landing seat = new Landing(slot.key().planSequence(), slot.position(), slot.planNodeKey());
        Landed landed = landings.computeIfAbsent(seat, key -> new Landed(slot));
        landed.rows.increment();
        if (settlement != null) {
            attribute(landed, row);
        }
        landedByNode.computeIfAbsent(slot.planNodeKey(), key -> new LongAdder()).increment();
        landedWrites++;
    }

    /** Attributes one landing to the cell of the window the writing thread runs. A write made
     * outside a task window, or through a window that carried no task token, makes the whole
     * landing unattributable instead of guessing the cell it belongs to. A landing whose write
     * named no identity keeps the batch's rows named only in part, and a settlement writes nothing
     * for it. */
    private void attribute(Landed landed, WorldWriteGuard.LandedRow row) {
        Window open = windows.get(Thread.currentThread());
        Object token = open == null ? null : open.token();
        if (!(token instanceof PipelineBatchLedger.TrackedTask task)) {
            landed.unattributed = true;
            return;
        }
        long cell = task.id().cell();
        long ordinal = task.id().ordinal();
        CellCount held = landed.cells.get(cell);
        if (held == null) {
            held = new CellCount(ordinal);
            landed.cells.put(cell, held);
        } else if (held.ordinal != ordinal) {
            // Two tasks of one cell cannot both be the writer this attribution names.
            landed.unattributed = true;
        }
        held.rows.increment();
        if (row == null) {
            landed.rowsNamed = false;
            return;
        }
        held.content.add(row.text(cell, ordinal));
    }

    /** The per-cell form of one report: empty and marked unattributed when a landing of the batch
     * was made outside a task window, so a consumer can only take the plain path for it. */
    private static Attributed attributedOf(Report report, Landed landed) {
        if (landed == null || landed.unattributed || landed.cells.isEmpty()) {
            return new Attributed(report, List.of(), false, false);
        }
        List<Long> ordered = new ArrayList<>(landed.cells.keySet());
        ordered.sort(Long::compareTo);
        List<CellRows> cells = new ArrayList<>(ordered.size());
        for (Long cell : ordered) {
            CellCount held = landed.cells.get(cell);
            cells.add(new CellRows(cell, held.ordinal,
                (int) Math.min(Integer.MAX_VALUE, held.rows.sum()), held.content));
        }
        return new Attributed(report, cells, true, landed.rowsNamed);
    }

    /** The claims the newest plan declared, in a fixed order. */
    public List<Claim> claims() {
        Map<String, Claim> current = declarations;
        List<String> keys = new ArrayList<>(current.keySet());
        keys.sort(String::compareTo);
        List<Claim> rows = new ArrayList<>(keys.size());
        for (String key : keys) {
            rows.add(current.get(key));
        }
        return rows;
    }

    /** The writes one declared job landed since the readings were cleared. */
    public long landedOf(String nodeKey) {
        LongAdder cell = landedByNode.get(nodeKey);
        return cell == null ? 0L : cell.sum();
    }

    /** How many claims the newest plan carries. */
    public int active() {
        return declarations.size();
    }

    /** How many windows are open right now. */
    public int openWindows() {
        return windows.size();
    }

    /** How many window frames are held right now, taken as the total over the threads that hold
     * one. A frame is counted from the moment the span that opened it seats its window until that
     * span ends, the tick moves past it or the window is turned off, so a frame a writer never
     * closed stays counted until one of those three points and no frame is counted twice. A window
     * a declaration never claimed holds no frame, and the bookkeeping holds one frame per open
     * window: the two readings therefore keep their own keys, and this is the one that keeps its
     * meaning once a thread can hold more than one frame. */
    public int openWindowFrames() {
        return windows.size();
    }

    public long declaredCount() {
        return declared;
    }

    public long seatedCount() {
        return seated;
    }

    public long disarmedCount() {
        return disarmed;
    }

    public long sweptCount() {
        return swept;
    }

    public long refusedCount() {
        return refused;
    }

    public long unclaimedWindowCount() {
        return unclaimedWindows;
    }

    /** The windows whose mailbox the placement table did not know and whose claim the plan declared
     * for the mailbox name; each of them seated the claim of every world that declared it. */
    public long unplacedWindowCount() {
        return unplacedWindows;
    }

    /** The world and mailbox of the newest window no declaration covered, so a face that claimed
     * nothing can name the mailbox it saw. */
    public String lastWindow() {
        return lastWindow;
    }

    public long offThreadWindowCount() {
        return offThreadWindows;
    }

    /** The spans a thread tried to nest inside its own window; a nested begin opens nothing. */
    public long overlappingWindowCount() {
        return overlappingWindows;
    }

    /** The ends whose token did not match the open window's; such an end closes nothing. */
    public long mismatchedEndCount() {
        return mismatchedEnds;
    }

    public long landedWriteCount() {
        return landedWrites;
    }

    public long reportedBatchCount() {
        return reportedBatches;
    }

    public long landedWithoutNodeCount() {
        return landedWithoutNode;
    }

    public long unclaimedDomainCount() {
        return unclaimedDomain;
    }

    public long foreignRefCount() {
        return foreignRefs;
    }

    public long emptyWriteSetCount() {
        return emptyWriteSet;
    }

    public long noMailboxCount() {
        return noMailbox;
    }

    public long noDemandCount() {
        return noDemand;
    }

    public long noWorldCount() {
        return noWorld;
    }

    public long noVersionCount() {
        return noVersion;
    }

    public long noEpochCount() {
        return noEpoch;
    }

    /** Clears every reading and every claim. */
    public void reset() {
        retireDeclared();
        closeAll();
        levelWorlds.clear();
        landings.clear();
        landedByNode.clear();
        authorizing = null;
        declarations = Map.of();
        byMailbox = new ConcurrentHashMap<>();
        declared = 0L;
        generations = 0L;
        seated = 0L;
        disarmed = 0L;
        swept = 0L;
        refused = 0L;
        claimedWindows = 0L;
        unclaimedWindows = 0L;
        unplacedWindows = 0L;
        offThreadWindows = 0L;
        overlappingWindows = 0L;
        mismatchedEnds = 0L;
        lastWindow = "";
        landedWrites = 0L;
        reportedBatches = 0L;
        landedWithoutNode = 0L;
        unclaimedDomain = 0L;
        foreignRefs = 0L;
        emptyWriteSet = 0L;
        noMailbox = 0L;
        noDemand = 0L;
        noWorld = 0L;
        noVersion = 0L;
        noEpoch = 0L;
    }

    /** Retires a window's key unless the newest declaration holds it too. */
    private boolean retireWindow(WriteControlledSlots.SlotKey key) {
        if (declaredSeats.contains(key)) {
            return false;
        }
        return table.retire(key);
    }

    /** Retires every seat the newest declaration made, so no plan keeps a slot the freeze replaced. */
    private void retireDeclared() {
        List<WriteControlledSlots.SlotKey> held = declaredSeats;
        declaredSeats = List.of();
        for (WriteControlledSlots.SlotKey key : held) {
            table.retire(key);
        }
    }

    private void closeAll() {
        for (Map.Entry<Thread, Window> entry : windows.entrySet()) {
            Window open = entry.getValue();
            if (windows.remove(entry.getKey(), open)) {
                for (WriteControlledSlots.SlotKey key : open.keys()) {
                    retireWindow(key);
                }
            }
        }
    }

    private Claim claimOf(JobGraph.Node node, long planSequence, long tick) {
        if (!WriteClaimTable.claims(node.domainId())) {
            unclaimedDomain++;
            return null;
        }
        String mailbox = mailboxOf(node);
        if (mailbox == null) {
            noMailbox++;
            return null;
        }
        Set<String> paths = new TreeSet<>();
        for (JobDeclaration.DomainRef ref : node.writeSet()) {
            if (!ref.worldId().equals(node.worldId()) || !ref.domainId().equals(node.domainId())) {
                foreignRefs++;
                continue;
            }
            paths.addAll(WriteClaimTable.pathsOf(ref.domainId()));
        }
        if (paths.isEmpty()) {
            emptyWriteSet++;
            return null;
        }
        WriteControlledSlots.WriteSpace space = writeSpaces.apply(node);
        if (space == null || !space.wellFormed()) {
            // A claim names the writes of one job, and a write is placed by the range it falls in.
            // A declaration that names no range leaves the write set unplaceable, so no slot is
            // declared for it and its writes keep the path they had.
            emptyWriteSet++;
            return null;
        }
        JobDeclaration.OwnerDemand demand = demandOf(node);
        if (demand == null) {
            noDemand++;
            return null;
        }
        Long granted = versions.grantedFor(planSequence, node.worldId(), WriteLevel.REGION,
            node.domainId());
        if (granted == null || granted == WriteVersionSlots.NOT_CARRIED) {
            // This plan authorized no generation for the node, so the write set stays unclaimed:
            // a row numbered with a value no grant of this plan answered would be a row whose
            // authorization cannot be traced back to the plan that put it there.
            noVersion++;
            return null;
        }
        long version = granted;
        long epoch = epochs.epochOf(node.worldId());
        if (epoch <= 0L) {
            noEpoch++;
            return null;
        }
        Object level = levelOf == null ? null : levelOf.apply(node.worldId());
        if (level == null) {
            noWorld++;
            return null;
        }
        levelWorlds.put(level, node.worldId());
        int hold = Math.max(1, demand.holdTicks());
        return new Claim(node.key(), node.worldId(), mailbox, node.domainId(), planSequence,
            node.position(), version, epoch, tick + hold, demand.holderKind(), demand.holderSiteId(),
            Set.copyOf(paths), level, space);
    }

    private static JobDeclaration.OwnerDemand demandOf(JobGraph.Node node) {
        for (JobDeclaration.OwnerDemand demand : node.ownerDemands()) {
            if (demand.worldId().equals(node.worldId()) && demand.domainId().equals(node.domainId())
                && demand.level() == WriteLevel.REGION) {
                return demand;
            }
        }
        return null;
    }

    /** The mailbox a node names: the last segment of its key, without the freeze suffix a batch
     * key carries. A node whose key does not name a mailbox cannot be seated by a mailbox window. */
    private static String mailboxOf(JobGraph.Node node) {
        if (node.key() == null || node.worldId() == null || node.domainId() == null) {
            return null;
        }
        String prefix = node.domainId() + "/" + node.worldId() + "/";
        if (!node.key().startsWith(prefix) || node.key().length() == prefix.length()) {
            return null;
        }
        String mailbox = node.key().substring(prefix.length());
        int freeze = mailbox.lastIndexOf('#');
        return freeze <= 0 ? mailbox : mailbox.substring(0, freeze);
    }

}