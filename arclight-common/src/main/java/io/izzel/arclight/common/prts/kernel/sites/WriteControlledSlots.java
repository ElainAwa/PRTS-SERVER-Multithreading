/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.sites;

import io.izzel.arclight.common.prts.kernel.auth.HolderKind;
import io.izzel.arclight.common.prts.kernel.auth.OwnerRegistry;
import io.izzel.arclight.common.prts.kernel.auth.OwnerToken;
import io.izzel.arclight.common.prts.kernel.auth.WriteLevel;
import io.izzel.arclight.common.prts.kernel.auth.WriteVersionSlots;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** The claim table of the controlled write channel. A slot declares a range for one world, domain
 * and level; a write no active range covers is not a domain write and stays on the default path,
 * and only a covered write whose contract still holds is captured. Nothing here decides a write by
 * itself: the guard hands a covered write to the judged path it already had. */
public final class WriteControlledSlots {

    public enum Verdict {
        DEFAULT_PATH,
        CAPTURED,
        REJECTED
    }

    /** Why a slot was refused when it was declared: declaration labels, not reject codes. */
    public enum Refusal {
        NONE,
        WILDCARD_KEY,
        EMPTY_KEY,
        EMPTY_WRITE_SET,
        PLAN_SEQUENCE_INVALID,
        DUPLICATE_KEY,
        OVERLAP,
        NOT_QUIESCENT
    }

    /** The identity of one seated slot generation: a caller that captured a slot keeps it and checks
     * it before a write, and a transfer or retirement makes the older handle stale. */
    public record SlotHandle(SlotKey key, long generation) {
    }

    /** The granularity a declared range is named at: a section is the 16x16x16 block volume. */
    public enum CellKind {
        CHUNK,
        SECTION,
        BLOCK
    }

    /** The block position one write is made at, or the absence of one: such a write is never matched
     * against a range. */
    public record WritePosition(boolean readable, int x, int y, int z) {

        public static final WritePosition NONE = new WritePosition(false, 0, 0, 0);

        public static WritePosition at(int x, int y, int z) {
            return new WritePosition(true, x, y, z);
        }
    }

    /** One declared spatial range: the cells are what membership is decided on; the fold only names
     * them. */
    public record WriteSpace(CellKind kind, long[] cells, String digest) {

        /** A declared bound, not a measured one: a range that names more is refused. */
        public static final int MAX_CELLS = 16;

        public static final WriteSpace NONE = new WriteSpace(null, new long[0], "");

        public WriteSpace {
            cells = canonical(cells);
            digest = kind == null || cells.length == 0 ? "" : foldCells(kind, cells);
        }

        public static WriteSpace of(CellKind kind, long... cells) {
            return new WriteSpace(kind, cells, "");
        }

        @Override
        public long[] cells() {
            return cells.clone();
        }

        /** Whether one write falls inside this range: the position is folded at this range's own
         * granularity. */
        public boolean covers(WritePosition position) {
            return wellFormed() && position != null && position.readable()
                && Arrays.binarySearch(cells, cellOf(kind, position)) >= 0;
        }

        public boolean contains(long cellKey) {
            return wellFormed() && Arrays.binarySearch(cells, cellKey) >= 0;
        }

        public int size() {
            return cells.length;
        }

        public boolean wellFormed() {
            return kind != null && cells.length >= 1 && cells.length <= MAX_CELLS;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof WriteSpace space && kind == space.kind
                && Arrays.equals(cells, space.cells);
        }

        @Override
        public int hashCode() {
            return 31 * Objects.hashCode(kind) + Arrays.hashCode(cells);
        }

        private static long[] canonical(long[] input) {
            if (input == null || input.length == 0) {
                return new long[0];
            }
            long[] sorted = input.clone();
            Arrays.sort(sorted);
            int kept = 0;
            for (int index = 0; index < sorted.length; index++) {
                if (index == 0 || sorted[index] != sorted[index - 1]) {
                    sorted[kept++] = sorted[index];
                }
            }
            return kept == sorted.length ? sorted : Arrays.copyOf(sorted, kept);
        }
    }

    /** The key of one controlled slot: the segment is the fixed token of the spatial range the slot
     * was frozen for. */
    public record SlotKey(String worldId, String domainId, WriteLevel level, String segment,
                          long planSequence) {

        public boolean wildcard() {
            return isWildcard(worldId) || isWildcard(domainId) || isWildcard(segment);
        }

        public boolean incomplete() {
            return blank(worldId) || blank(domainId) || blank(segment) || level == null;
        }

        /** The stable name of one key, so a reader that needs a fixed order over slots has one. */
        public String rank() {
            return worldId + "|" + domainId + "|" + level + "|" + segment + "|" + planSequence;
        }

        /** The fixed encoding of one range as the segment of a slot key: the granularity and every
         * cell key in ascending order, so a key names its range exactly and not by a fold of it. */
        public static String segmentOf(WriteSpace space) {
            if (space == null || !space.wellFormed()) {
                return "";
            }
            long[] cells = space.cells();
            StringBuilder token = new StringBuilder(cells.length * 17 + 8);
            token.append(space.kind()).append(':');
            for (int index = 0; index < cells.length; index++) {
                if (index > 0) {
                    token.append(',');
                }
                token.append(Long.toUnsignedString(cells[index], 16));
            }
            return token.toString();
        }

        private static boolean isWildcard(String value) {
            return "*".equals(value);
        }

        private static boolean blank(String value) {
            return value == null || value.isEmpty();
        }
    }

    /** The fold of a declared write set over its keys: two sets that declare the same keys fold to
     * the same digest. */
    public static String fold(Collection<String> writeSet) {
        return Long.toHexString(foldValue(writeSet));
    }

    /** The same fold as a value: the number a producer reports and the string form are one. */
    public static long foldValue(Collection<String> writeSet) {
        long hash = FOLD_BASIS;
        for (String key : new TreeSet<>(writeSet == null ? Set.of() : writeSet)) {
            for (int index = 0; index < key.length(); index++) {
                hash = (hash ^ key.charAt(index)) * FOLD_PRIME;
            }
        }
        return hash;
    }

    private static String foldCells(CellKind kind, long[] cells) {
        List<String> tokens = new ArrayList<>(cells.length);
        for (long cell : cells) {
            tokens.add(kind.name() + ":" + Long.toUnsignedString(cell, 16));
        }
        return fold(tokens);
    }

    // The section key is the packing the game itself uses, so no bias of its own is needed.
    private static long cellOf(CellKind kind, WritePosition position) {
        return switch (kind) {
            case CHUNK -> ChunkPos.asLong(position.x() >> 4, position.z() >> 4);
            case SECTION -> SectionPos.asLong(position.x() >> 4, position.y() >> 4, position.z() >> 4);
            case BLOCK -> BlockPos.asLong(position.x(), position.y(), position.z());
        };
    }

    /** One declared slot: the spatial range it covers, the write set it declares and the contract a
     * covered write has to satisfy. */
    public record Slot(SlotKey key, Object levelRef, String planNodeKey, int position,
                       long expectedVersion, long slotGeneration, long worldEpoch, long expireTick,
                       HolderKind holderKind, String holderSiteId, Set<String> writeSet,
                       WriteSpace space) {

        /** A slot that names no spatial range: it is refused when declared, never covering a write. */
        public Slot(SlotKey key, Object levelRef, String planNodeKey, int position,
                    long expectedVersion, long slotGeneration, long worldEpoch, long expireTick,
                    HolderKind holderKind, String holderSiteId, Set<String> writeSet) {
            this(key, levelRef, planNodeKey, position, expectedVersion, slotGeneration, worldEpoch,
                expireTick, holderKind, holderSiteId, writeSet, WriteSpace.NONE);
        }

        public Slot {
            writeSet = writeSet == null ? Set.of() : Set.copyOf(writeSet);
            space = space == null ? WriteSpace.NONE : space;
        }

        public String pathDigest() {
            return fold(writeSet);
        }

        /** The name the readings gave the write set fold; it is the same number. */
        public String writeSetDigest() {
            return pathDigest();
        }

        public String spaceDigest() {
            return space.digest();
        }

        public boolean covers(WritePosition position) {
            return space.covers(position);
        }
    }

    /** The facts the table cannot read from its own registries: which world a level belongs to, and
     * which step the frozen plan holds for a slot. */
    public interface Source {

        /** The world a write is happening in, as the platform names it; empty while nothing is wired. */
        String worldOf(Object levelRef);

        /** The step the plan holds for a slot, or null while the plan does not carry it. */
        Step stepOf(String worldId, String domainId, String planNodeKey);

        Source NONE = new Source() {

            @Override
            public String worldOf(Object levelRef) {
                return "";
            }

            @Override
            public Step stepOf(String worldId, String domainId, String planNodeKey) {
                return null;
            }
        };

        record Step(long planSequence, int position) {
        }
    }

    /** One judgement: a captured write carries the plan identity the contract resolved, a rejected
     * one names the term that failed. */
    public record Decision(Verdict verdict, SlotKey key, String planNodeKey, long planSequence,
                           int position, String pathDigest, String spaceDigest, int covering,
                           String reason) {

        /** The name the readings gave the path digest; it is the same number. */
        public String writeSetDigest() {
            return pathDigest;
        }

        private static Decision defaultPath(String reason) {
            return new Decision(Verdict.DEFAULT_PATH, null, "", 0L, -1, "", "", 0, reason);
        }
    }

    /** The refusal of one covered write, with the elements a diagnosis needs. */
    public record WriteRefusal(String reason, String worldId, String domainId, WriteLevel level,
                          String segment, long planSequence, String planNodeKey, String siteId,
                          HolderKind holderKind, long tickIndex, long expectedVersion,
                          long carriedVersion, long worldEpoch, long slotGeneration,
                          String pathDigest, String spaceDigest, int covering) {

        /** The name the readings gave the path digest; it is the same number. */
        public String writeSetDigest() {
            return pathDigest;
        }
    }

    /** One published seat: the slot and the generation that covers it leave the table's hands in a
     * single map write, so no reader can see one without the other. */
    private record Seat(Slot slot, long generation) {
    }

    private static final long FOLD_BASIS = 0xcbf29ce484222325L;
    private static final long FOLD_PRIME = 0x100000001b3L;
    private static final String REASON_UNREADABLE_POSITION = "segment";
    private static final String REASON_AMBIGUOUS_RANGE = "owner";

    public static final WriteControlledSlots NOTHING = new WriteControlledSlots(new OwnerRegistry(),
        WriteVersionSlots.NOTHING, new WorldEpochs(), Source.NONE);

    /** Seats the retained declarations that can cover one position before the write is judged. It
     * returns how many slots it registered; zero leaves the table exactly as it was. */
    @FunctionalInterface
    public interface LazySeat {

        int seat(Object levelRef, String domainKey, WritePosition position, long tickIndex);
    }

    private final OwnerRegistry owners;
    private final WriteVersionSlots versions;
    private final WorldEpochs epochs;
    private final Source source;
    private volatile LazySeat lazySeat = (level, domain, position, tick) -> 0;
    private final Map<SlotKey, Seat> seats = new ConcurrentHashMap<>();
    private final Map<SlotKey, Long> openUses = new ConcurrentHashMap<>();
    private final Set<SlotKey> declarationHeld = ConcurrentHashMap.newKeySet();
    private final Map<SlotKey, OwnerToken> declarationOwners = new ConcurrentHashMap<>();
    private final AtomicLong registered = new AtomicLong();
    private final AtomicLong renewed = new AtomicLong();
    private final AtomicLong advanced = new AtomicLong();
    private final AtomicLong refused = new AtomicLong();
    private final Map<Refusal, AtomicLong> refusals = new ConcurrentHashMap<>();
    private final AtomicLong attempts = new AtomicLong();
    private final AtomicLong defaultPath = new AtomicLong();
    private final AtomicLong captured = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong notExecutable = new AtomicLong();
    private volatile boolean enabled;
    private volatile Decision lastDecision;
    private final ThreadLocal<WriteRefusal> lastRefusal = new ThreadLocal<>();
    private volatile Slot lastRefusingSlot;
    private final ThreadLocal<Slot> lastCoveredSlot = new ThreadLocal<>();

    public WriteControlledSlots(OwnerRegistry owners, WriteVersionSlots versions, WorldEpochs epochs,
                                Source source) {
        this.owners = owners;
        this.versions = versions;
        this.epochs = epochs;
        this.source = source;
    }

    /** Decides whether the claim table is consulted at all: off, every write is counted as a
     * default-path write and no slot is looked up. */
    public void refresh(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    /** Names the retained declarations a write may lazy-seat before it is judged. */
    public void lazySeat(LazySeat seated) {
        lazySeat = seated == null ? (level, domain, position, tick) -> 0 : seated;
    }

    /** Declares one slot. A slot that names no usable range, or whose key does not spell the range
     * it carries, is refused here and not repaired when a write arrives. A newer generation of a key
     * replaces the seated one as a transfer, but only while no captured write still holds it. */
    public Refusal register(Slot slot) {
        return registerInternal(slot, false, null);
    }

    /** Registers one declaration-held slot without a frozen owner token. */
    public Refusal register(Slot slot, boolean fromDeclaration) {
        return registerInternal(slot, fromDeclaration, null);
    }

    /** Registers one declaration-held slot with the owner token it carried at declaration. The
     * token is an internal marker; a manual slot still takes the live owner lookup. */
    public Refusal register(Slot slot, OwnerToken declarationOwner) {
        return registerInternal(slot, true, declarationOwner);
    }

    /** Registers a batch of manual slots all or none: the keys are visited in the table's own stable
     * order, and a refusal restores every seat the call changed, so a batch never leaves half of its
     * ranges seated. */
    public Refusal registerAll(List<Slot> batch) {
        if (batch == null || batch.isEmpty()) {
            return Refusal.NONE;
        }
        List<Slot> ordered = new ArrayList<>(batch);
        ordered.sort(Comparator.comparing(slot ->
            slot == null || slot.key() == null ? "" : slot.key().rank()));
        Map<SlotKey, Seat> previousSeats = new LinkedHashMap<>();
        Map<SlotKey, Boolean> previousHeld = new LinkedHashMap<>();
        Map<SlotKey, OwnerToken> previousOwners = new LinkedHashMap<>();
        List<SlotKey> touched = new ArrayList<>();
        int seated = 0;
        int renewedNow = 0;
        for (Slot slot : ordered) {
            SlotKey key = slot == null ? null : slot.key();
            if (key != null && !previousSeats.containsKey(key)) {
                previousSeats.put(key, seats.get(key));
                previousHeld.put(key, declarationHeld.contains(key));
                previousOwners.put(key, declarationOwners.get(key));
                touched.add(key);
            }
            boolean wasSeated = key != null && seats.get(key) != null;
            Refusal refusal = registerInternal(slot, false, null);
            if (refusal != Refusal.NONE) {
                for (SlotKey changed : touched) {
                    Seat old = previousSeats.get(changed);
                    if (old == null) {
                        seats.remove(changed);
                    } else {
                        seats.put(changed, old);
                    }
                    if (previousHeld.get(changed)) {
                        declarationHeld.add(changed);
                    } else {
                        declarationHeld.remove(changed);
                    }
                    OwnerToken owner = previousOwners.get(changed);
                    if (owner == null) {
                        declarationOwners.remove(changed);
                    } else {
                        declarationOwners.put(changed, owner);
                    }
                }
                registered.addAndGet(-(long) seated);
                renewed.addAndGet(-(long) renewedNow);
                return refusal;
            }
            seated++;
            if (wasSeated) {
                renewedNow++;
            }
        }
        return Refusal.NONE;
    }

    private Refusal registerInternal(Slot slot, boolean fromDeclaration, OwnerToken declarationOwner) {
        Refusal refusal = refusalOf(slot);
        if (refusal != Refusal.NONE) {
            noteRefusal(refusal);
            return refusal;
        }
        SlotKey key = slot.key();
        Seat seated = seats.get(key);
        if (seated != null && seated.slot() != null) {
            if (seated.generation() == slot.slotGeneration()) {
                noteRefusal(Refusal.DUPLICATE_KEY);
                return Refusal.DUPLICATE_KEY;
            }
            // A newer generation replaces the seated one only when no captured write still holds
            // it: a transfer that overlapped a write would change the right under that write.
            if (!quiescent(key)) {
                noteRefusal(Refusal.NOT_QUIESCENT);
                return Refusal.NOT_QUIESCENT;
            }
        }
        if (overlapsAnotherHolder(slot)) {
            noteRefusal(Refusal.OVERLAP);
            return Refusal.OVERLAP;
        }
        if (seated != null) {
            renewed.incrementAndGet();
        }
        if (fromDeclaration) {
            declarationHeld.add(key);
            if (declarationOwner != null) {
                declarationOwners.put(key, declarationOwner);
            } else {
                declarationOwners.remove(key);
            }
        } else {
            declarationHeld.remove(key);
            declarationOwners.remove(key);
        }
        // The seat and its declaration face are published only after both are ready; the seat is
        // the one write a reader of the ranges sees, so it goes last.
        seats.put(key, new Seat(slot, slot.slotGeneration()));
        registered.incrementAndGet();
        return Refusal.NONE;
    }

    /** The owner token a declaration-held slot carried when it was registered, or null for a
     * manual slot. The write path uses it ahead of the live registry lookup. */
    OwnerToken declarationOwnerOf(SlotKey key) {
        return key == null ? null : declarationOwners.get(key);
    }

    /** Retires the slot of one key: the writes it covered fall back to the default path. */
    public boolean retire(SlotKey key) {
        declarationHeld.remove(key);
        declarationOwners.remove(key);
        openUses.remove(key);
        return seats.remove(key) != null;
    }

    /** Retires the generation of one key without seating a new slot: what the seated slot covers is
     * refused from here on. */
    public long advance(SlotKey key) {
        long[] minted = new long[1];
        seats.compute(key, (ignored, current) -> {
            long generation = (current == null ? 0L : current.generation()) + 1L;
            minted[0] = generation;
            return new Seat(current == null ? null : current.slot(), generation);
        });
        advanced.incrementAndGet();
        return minted[0];
    }

    /** Opens one captured use of a key: while a use is open the table will not transfer the slot to
     * another generation, because the write that captured it still names the seated one. */
    public void beginUse(SlotKey key) {
        if (key != null) {
            openUses.merge(key, 1L, Long::sum);
        }
    }

    /** Closes one captured use opened by {@link #beginUse(SlotKey)}. */
    public void endUse(SlotKey key) {
        if (key != null) {
            openUses.computeIfPresent(key, (ignored, uses) -> uses > 1L ? uses - 1L : null);
        }
    }

    /** Whether no captured write still holds the seated slot of one key. */
    public boolean quiescent(SlotKey key) {
        Long uses = key == null ? null : openUses.get(key);
        return uses == null || uses <= 0L;
    }

    /** The handle of the seated generation of one key, or null while the key has no slot. */
    public SlotHandle seated(SlotKey key) {
        Seat seat = key == null ? null : seats.get(key);
        return seat == null || seat.slot() == null ? null
            : new SlotHandle(key, seat.generation());
    }

    /** Whether one handle still names the seated generation of its key. */
    public boolean holds(SlotHandle handle) {
        if (handle == null || handle.key() == null) {
            return false;
        }
        Seat seat = seats.get(handle.key());
        return seat != null && seat.slot() != null && seat.generation() == handle.generation();
    }

    /** Judges one write of one write path against the table. A caller that cannot name the position
     * says so and the write is refused rather than matched at world level. */
    public Decision judge(Object levelRef, String domainKey, WritePosition position, long tickIndex) {
        if (levelRef == null || domainKey == null || domainKey.isEmpty()) {
            notExecutable.incrementAndGet();
            return lastDecision = Decision.defaultPath("NOT-EXECUTABLE");
        }
        attempts.incrementAndGet();
        if (!enabled) {
            defaultPath.incrementAndGet();
            return lastDecision = Decision.defaultPath("");
        }
        List<Slot> candidates = candidates(levelRef, domainKey);
        boolean lazyTried = false;
        if (candidates.isEmpty()) {
            // A retained declaration may cover the write although no slot was seated yet: ask it
            // once before the default path, then judge the registered candidates normally.
            lazyTried = true;
            if (lazySeat.seat(levelRef, domainKey,
                position == null ? WritePosition.NONE : position, tickIndex) > 0) {
                candidates = candidates(levelRef, domainKey);
            }
        }
        if (candidates.isEmpty()) {
            defaultPath.incrementAndGet();
            return lastDecision = Decision.defaultPath("");
        }
        if (position == null || !position.readable()) {
            // The write could be inside a declared range and the position it is made at cannot be
            // read: refusing is the only answer that does not match it against a whole world.
            return refuse(candidates.get(0), REASON_UNREADABLE_POSITION, 0, tickIndex);
        }
        Map<String, Slot> covering = covering(candidates, position);
        if (covering.isEmpty() && !lazyTried) {
            lazyTried = true;
            if (lazySeat.seat(levelRef, domainKey, position, tickIndex) > 0) {
                candidates = candidates(levelRef, domainKey);
                covering = covering(candidates, position);
            }
        }
        if (covering.isEmpty()) {
            defaultPath.incrementAndGet();
            return lastDecision = Decision.defaultPath("");
        }
        if (covering.size() > 1) {
            // Two active ranges cover the same write, so which job wrote it cannot be decided and
            // neither the first one found nor the lowest ranked one may stand for both.
            return refuse(covering.values().iterator().next(), REASON_AMBIGUOUS_RANGE,
                covering.size(), tickIndex);
        }
        Slot slot = covering.values().iterator().next();
        lastCoveredSlot.set(slot);
        String world = source.worldOf(levelRef);
        Source.Step step = source.stepOf(slot.key().worldId(), slot.key().domainId(),
            slot.planNodeKey());
        if (step == null && declarationHeld.contains(slot.key())) {
            step = new Source.Step(slot.key().planSequence(), slot.position());
        }
        WriteRefusal refusal = inspect(slot, world, step, tickIndex, covering.size());
        if (refusal != null) {
            rejected.incrementAndGet();
            lastRefusal.set(refusal);
            lastRefusingSlot = slot;
            lastCoveredSlot.remove();
            return lastDecision = new Decision(Verdict.REJECTED, slot.key(), slot.planNodeKey(), 0L,
                -1, slot.pathDigest(), slot.spaceDigest(), covering.size(), refusal.reason());
        }
        captured.incrementAndGet();
        return lastDecision = new Decision(Verdict.CAPTURED, slot.key(), slot.planNodeKey(),
            step.planSequence(), step.position(), slot.pathDigest(), slot.spaceDigest(),
            covering.size(), "");
    }

    /** Judges one write whose caller cannot name the position it is made at. */
    public Decision judge(Object levelRef, String domainKey, long tickIndex) {
        return judge(levelRef, domainKey, WritePosition.NONE, tickIndex);
    }

    private static Map<String, Slot> covering(List<Slot> candidates, WritePosition position) {
        Map<String, Slot> covering = new LinkedHashMap<>();
        for (Slot slot : candidates) {
            if (slot.space().covers(position)) {
                covering.putIfAbsent(identityOf(slot), slot);
            }
        }
        return covering;
    }

    private List<Slot> candidates(Object levelRef, String domainKey) {
        List<Slot> found = new ArrayList<>();
        String world = levelRef == null ? "" : source.worldOf(levelRef);
        for (Seat seat : seats.values()) {
            Slot slot = seat.slot();
            if (slot == null) {
                continue;
            }
            boolean sameLevel = slot.levelRef() == levelRef;
            boolean sameWorld = !world.isEmpty() && slot.key().worldId().equals(world);
            if ((sameLevel || sameWorld) && slot.writeSet().contains(domainKey)
                && slot.space().wellFormed()) {
                found.add(slot);
            }
        }
        return found;
    }

    // Two entries naming one slot instance are one covering slot, not two.
    private static String identityOf(Slot slot) {
        return slot.key().rank() + "#" + slot.slotGeneration();
    }

    /** Whether a slot's range intersects the range of a slot another holder seated for a domain the
     * two write sets share. The check runs when a slot is declared, so an overlap is refused before a
     * write has to pick between two covering ranges. */
    private boolean overlapsAnotherHolder(Slot candidate) {
        for (Seat seat : seats.values()) {
            Slot other = seat.slot();
            if (other == null || other.key().equals(candidate.key())
                || !other.key().worldId().equals(candidate.key().worldId())
                || sameHolder(other, candidate)
                || java.util.Collections.disjoint(other.writeSet(), candidate.writeSet())) {
                continue;
            }
            if (spaceOverlaps(other.space(), candidate.space())) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameHolder(Slot first, Slot second) {
        return first.holderKind() == second.holderKind()
            && first.holderSiteId().equals(second.holderSiteId());
    }

    private static boolean spaceOverlaps(WriteSpace first, WriteSpace second) {
        if (first == null || second == null
            || !first.wellFormed() || !second.wellFormed()) {
            return false;
        }
        for (long firstCell : first.cells()) {
            for (long secondCell : second.cells()) {
                if (cellsOverlap(first.kind(), firstCell, second.kind(), secondCell)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean cellsOverlap(CellKind firstKind, long firstCell,
                                        CellKind secondKind, long secondCell) {
        long[] first = bounds(firstKind, firstCell);
        long[] second = bounds(secondKind, secondCell);
        return first[0] <= second[1] && second[0] <= first[1]
            && first[2] <= second[3] && second[2] <= first[3]
            && first[4] <= second[5] && second[4] <= first[5];
    }

    // The inclusive block bounds of one cell: the chunk covers every height, the section a 16-cube
    // and the block a single point.
    private static long[] bounds(CellKind kind, long cell) {
        return switch (kind) {
            case CHUNK -> {
                long x = (long) ChunkPos.getX(cell) << 4;
                long z = (long) ChunkPos.getZ(cell) << 4;
                yield new long[]{x, x + 15L, Long.MIN_VALUE, Long.MAX_VALUE, z, z + 15L};
            }
            case SECTION -> {
                long x = (long) SectionPos.x(cell) << 4;
                long y = (long) SectionPos.y(cell) << 4;
                long z = (long) SectionPos.z(cell) << 4;
                yield new long[]{x, x + 15L, y, y + 15L, z, z + 15L};
            }
            case BLOCK -> {
                long x = BlockPos.getX(cell);
                long y = BlockPos.getY(cell);
                long z = BlockPos.getZ(cell);
                yield new long[]{x, x, y, y, z, z};
            }
        };
    }

    /** The exact range one seat was declared with, so a reader can verify membership against the
     * cells instead of the fold. */
    public WriteSpace spaceOf(SlotKey key) {
        Seat seat = key == null ? null : seats.get(key);
        return seat == null || seat.slot() == null ? WriteSpace.NONE : seat.slot().space();
    }

    private Decision refuse(Slot slot, String reason, int covering, long tickIndex) {
        SlotKey key = slot.key();
        long carried = versions.carried(key.worldId(), key.level(), key.domainId());
        WriteRefusal refusal = refusal(slot, reason, carried, tickIndex, covering);
        rejected.incrementAndGet();
        lastRefusal.set(refusal);
        lastRefusingSlot = slot;
        lastCoveredSlot.remove();
        return lastDecision = new Decision(Verdict.REJECTED, key, slot.planNodeKey(), 0L, -1,
            slot.pathDigest(), slot.spaceDigest(), covering, reason);
    }

    private WriteRefusal inspect(Slot slot, String world, Source.Step step, long tickIndex,
                                 int covering) {
        SlotKey key = slot.key();
        long carried = versions.carried(key.worldId(), key.level(), key.domainId());
        Long granted = versions.grantedFor(key.planSequence(), key.worldId(), key.level(),
            key.domainId());
        if (world.isEmpty() || !world.equals(key.worldId())) {
            return refusal(slot, "world", carried, tickIndex, covering);
        }
        OwnerToken token = declarationOwners.get(key);
        if (token != null && (!token.worldId().equals(key.worldId())
            || token.level() != key.level() || !token.domainId().equals(key.domainId()))) {
            return refusal(slot, "owner", carried, tickIndex, covering);
        }
        OwnerToken liveToken = owners.lookup(key.worldId(), key.level(), key.domainId()).orElse(null);
        if (token != null) {
            // The declared credential has to still name the installed right, generation included:
            // a released, retired or replaced one leaves the write without an owner.
            if (liveToken == null
                || liveToken.holderKind() != token.holderKind()
                || !liveToken.holderSiteId().equals(token.holderSiteId())
                || liveToken.epoch() != token.epoch()
                || liveToken.planSequence() != token.planSequence()
                || liveToken.worldEpoch() != token.worldEpoch()) {
                return refusal(slot, "owner", carried, tickIndex, covering);
            }
        } else {
            token = liveToken;
        }
        if (token == null || token.holderKind() != slot.holderKind()
            || !token.holderSiteId().equals(slot.holderSiteId())) {
            return refusal(slot, "owner", carried, tickIndex, covering);
        }
        if (token.expiredAt(tickIndex) || slot.expireTick() <= 0L || tickIndex >= slot.expireTick()) {
            return refusal(slot, "expiry", carried, tickIndex, covering);
        }
        // The generation the row wears has to be the one its own plan's grant answered: a plan
        // that granted nothing here, and a row that names a generation of its own, are both a
        // version this row does not carry, and the drift of the live slot is read the same way.
        boolean grantMismatch = granted != null && granted != slot.expectedVersion();
        boolean missingGrant = granted == null && !declarationHeld.contains(slot.key());
        if (grantMismatch || missingGrant
            || carried <= WriteVersionSlots.NOT_CARRIED || carried != slot.expectedVersion()
            || token.expectedVersion() != slot.expectedVersion()) {
            return refusal(slot, "version", carried, tickIndex, covering);
        }
        Seat seated = seats.get(key);
        if (seated == null || seated.slot() == null
            || seated.generation() != slot.slotGeneration()) {
            return refusal(slot, "generation", carried, tickIndex, covering);
        }
        long live = epochs.epochOf(key.worldId());
        if (live <= 0L || live != slot.worldEpoch()) {
            return refusal(slot, "world_epoch", carried, tickIndex, covering);
        }
        if (slot.planNodeKey().isEmpty()) {
            return refusal(slot, "plan", carried, tickIndex, covering);
        }
        if (step == null && !declarationHeld.contains(key)) {
            return refusal(slot, "plan", carried, tickIndex, covering);
        }
        if (step != null && step.planSequence() != key.planSequence()) {
            return refusal(slot, "plan_sequence", carried, tickIndex, covering);
        }
        return null;
    }

    private WriteRefusal refusal(Slot slot, String reason, long carried, long tickIndex,
                                 int covering) {
        SlotKey key = slot.key();
        return new WriteRefusal(reason, key.worldId(), key.domainId(), key.level(), key.segment(),
            key.planSequence(), slot.planNodeKey(), slot.holderSiteId(), slot.holderKind(), tickIndex,
            slot.expectedVersion(), carried, slot.worldEpoch(), slot.slotGeneration(),
            slot.pathDigest(), slot.spaceDigest(), covering);
    }

    private static Refusal refusalOf(Slot slot) {
        if (slot == null || slot.key() == null) {
            return Refusal.EMPTY_KEY;
        }
        if (slot.key().wildcard()) {
            return Refusal.WILDCARD_KEY;
        }
        if (!namesItsSpace(slot)) {
            return Refusal.EMPTY_WRITE_SET;
        }
        if (slot.key().incomplete()) {
            return Refusal.EMPTY_KEY;
        }
        if (slot.writeSet().isEmpty()) {
            return Refusal.EMPTY_WRITE_SET;
        }
        if (slot.key().planSequence() <= 0L) {
            return Refusal.PLAN_SEQUENCE_INVALID;
        }
        return Refusal.NONE;
    }

    private static boolean namesItsSpace(Slot slot) {
        WriteSpace space = slot.space();
        return space.wellFormed() && SlotKey.segmentOf(space).equals(slot.key().segment());
    }

    private void noteRefusal(Refusal refusal) {
        refused.incrementAndGet();
        refusals.computeIfAbsent(refusal, key -> new AtomicLong()).incrementAndGet();
    }

    public int slots() {
        int count = 0;
        for (Seat seat : seats.values()) {
            if (seat.slot() != null) {
                count++;
            }
        }
        return count;
    }

    public long registeredCount() {
        return registered.get();
    }

    public long renewedCount() {
        return renewed.get();
    }

    public long advancedCount() {
        return advanced.get();
    }

    public long refusedCount() {
        return refused.get();
    }

    public long refusedCount(Refusal refusal) {
        AtomicLong cell = refusals.get(refusal);
        return cell == null ? 0L : cell.get();
    }

    public long attemptsCount() {
        return attempts.get();
    }

    public long defaultPathCount() {
        return defaultPath.get();
    }

    public long capturedCount() {
        return captured.get();
    }

    public long rejectedCount() {
        return rejected.get();
    }

    /** How often a caller handed the table nothing to judge, counted on its own so the three
     * verdicts stay the three a write can leave with. */
    public long notExecutableCount() {
        return notExecutable.get();
    }

    /** The conservation of the claim face: every write the table judged left by exactly one of the
     * three verdicts, so a claim cannot hide a write and no write is judged twice. */
    public boolean conservationHolds() {
        return captured.get() + defaultPath.get() + rejected.get() == attempts.get();
    }

    public Decision lastDecision() {
        return lastDecision;
    }

    public WriteRefusal lastRefusal() {
        return lastRefusal.get();
    }

    public Slot lastRefusingSlot() {
        return lastRefusingSlot;
    }

    /** The slot the newest judgement was taken against, captured or refused: the thread that made
     * the write reads it to carry the claim it just handed on. */
    public Slot lastCoveredSlot() {
        return lastCoveredSlot.get();
    }

    public void reset() {
        seats.clear();
        openUses.clear();
        declarationHeld.clear();
        declarationOwners.clear();
        registered.set(0L);
        renewed.set(0L);
        advanced.set(0L);
        refused.set(0L);
        refusals.clear();
        attempts.set(0L);
        defaultPath.set(0L);
        captured.set(0L);
        rejected.set(0L);
        notExecutable.set(0L);
        lastDecision = null;
        lastRefusal.remove();
        lastRefusingSlot = null;
        lastCoveredSlot.remove();
    }
}
