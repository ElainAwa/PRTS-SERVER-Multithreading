/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.jobs;

import io.izzel.arclight.common.prts.kernel.commit.CommitLog;
import io.izzel.arclight.common.prts.kernel.commit.FrozenDeclarations;
import io.izzel.arclight.common.prts.kernel.shares.ShareClass;
import io.izzel.arclight.common.prts.support.PrtsChunkDemand;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the chunk demands one freeze saw as shadow candidates: every column is a parameter of the one
 * call that asked for the chunk, the freeze that read it, or a pure function of those two. What one
 * window becomes is handed over through the two entry points of this type and only from the very list
 * the one read returned; this type seals nothing, mints nothing and derives no key a second time.
 */
public final class DemandShadowCandidates implements PrtsChunkDemand.DemandTap {

    public static final String DOMAIN = "chunk-materialize";

    /** What a capture that is no declaration reads; nothing may be sealed from it. */
    public static final String NOT_WIRED = "NOT_WIRED";

    /** A capture carrying every column a declaration carries reads this while no witness exists. */
    public static final String NOT_EXECUTABLE = "NOT_EXECUTABLE";

    public static final String LATE_BEFORE_FREEZE = "late-before-freeze";

    public static final int CAP = 64;

    /** The parameters of one ask for a chunk as that call named them, and the chunk it named packed
     * by the platform's own function. An ask that named no coordinates carries no chunk: the origin
     * is a chunk a caller named, and nothing here turns "not named" into it. */
    public record DemandDescriptor(String worldId, String targetStatus, boolean requireChunk,
                                   FrozenDeclarations.DeclaredChunk chunk) {

        public DemandDescriptor {
            worldId = worldId == null ? "" : worldId;
            targetStatus = targetStatus == null ? "" : targetStatus;
        }

        public static DemandDescriptor at(String worldId, int chunkX, int chunkZ, String status,
                                          boolean requireChunk) {
            return new DemandDescriptor(worldId, status, requireChunk,
                new FrozenDeclarations.DeclaredChunk(ChunkPos.asLong(chunkX, chunkZ)));
        }

        public static DemandDescriptor withoutChunk(String worldId, String status) {
            return new DemandDescriptor(worldId, status, false, null);
        }
    }

    public record Freeze(long planSequence, long planGeneration, FrozenDeclarations.FreezeKey key) {

        public Freeze {
            key = key == null ? FrozenDeclarations.FreezeKey.NONE : key;
        }
    }

    public interface LiveWorldEpoch {

        long epochOf(String worldId);
    }

    /** One shadow candidate: what one call asked for, and the freeze that read it. */
    public record Shadow(String worldId, String domainId, String nodeKey,
                         FrozenDeclarations.DeclaredChunk chunk, String targetStatus,
                         boolean requireChunk, long worldEpoch, long planSequence, long planGeneration,
                         FrozenDeclarations.FreezeKey freeze, boolean late) {

        public Shadow {
            worldId = worldId == null ? "" : worldId;
            domainId = domainId == null ? "" : domainId;
            nodeKey = nodeKey == null ? "" : nodeKey;
            targetStatus = targetStatus == null ? "" : targetStatus;
            freeze = freeze == null ? FrozenDeclarations.FreezeKey.NONE : freeze;
        }

        /** Whether this capture carries every column a declaration carries. Presence is the chunk
         * object and never its packed value: the origin packs as no chunk and both are legal. */
        public boolean declared() {
            return !worldId.isEmpty() && !domainId.isEmpty() && !nodeKey.isEmpty() && chunk != null
                && !targetStatus.isEmpty() && CommitLog.liveWorldEpoch(worldEpoch);
        }

        /** A complete capture still reads unexecutable: the freeze has no witness for it. */
        public String state() {
            return declared() && !late ? NOT_EXECUTABLE : NOT_WIRED;
        }

        public String refusal() {
            String epoch = CommitLog.worldEpochRefusal(worldEpoch);
            if (!epoch.isEmpty()) {
                return epoch;
            }
            return late ? LATE_BEFORE_FREEZE : "";
        }

        /** Whether the frozen generation is still the live one: re-reading and comparing is what a
         * drift check does, and writing the live value back would read a rebuilt world as current. */
        public boolean currentFor(long liveWorldEpoch) {
            return declared() && liveWorldEpoch == worldEpoch;
        }
    }

    /** Whether an operation already done before the freeze refuses the capture. It does: what a
     * freeze declares has to be outstanding at it. The other answer is not its negation - a handle
     * that is not done may have started - so it authorizes nothing either. */
    public static boolean lateBeforeFreeze(boolean doneBeforeFreeze) {
        return doneBeforeFreeze;
    }

    /** The key a captured demand would be declared under: a pure function of the capture. */
    public static String keyOf(DemandDescriptor descriptor) {
        DemandDescriptor named = descriptor == null
            ? new DemandDescriptor("", "", false, null) : descriptor;
        String chunk = named.chunk() == null ? "-" : Long.toString(named.chunk().packedPos());
        return DOMAIN + "/" + named.worldId() + "/" + chunk + "/" + named.targetStatus();
    }

    private static final class Slot {

        private DemandDescriptor descriptor;
        private boolean completionSeen;
    }

    private final Slot[] ring = new Slot[CAP];
    private int head;
    private int size;
    private long windows;
    private long captured;
    private long dropped;
    private long completions;
    private volatile List<Shadow> last = List.of();
    private long unexecutable;
    private long notWired;
    private long lateRefusals;
    private long unshaped;
    private long unlive;

    public DemandShadowCandidates() {
        for (int index = 0; index < CAP; index++) {
            ring[index] = new Slot();
        }
    }

    @Override
    public void demandStarted(String worldId, String status, boolean blocking) {
        // A synchronous ask is not a future and holds no pending operation a freeze could declare.
    }

    @Override
    public void demandFinished(String worldId, String status, boolean blocking, boolean satisfied) {
    }

    @Override
    public void futureOpened(String worldId, String status) {
        capture(DemandDescriptor.withoutChunk(worldId, status));
    }

    @Override
    public long futureOpenedAt() {
        return -1L;
    }

    @Override
    public void futureTaken(String worldId, String status) {
    }

    @Override
    public void futureCompleted(String worldId, String status, long startedNanos, boolean satisfied) {
        markCompleted(worldId, status);
    }

    @Override
    public void demandDescriptor(String worldId, int chunkX, int chunkZ, String status,
                                 boolean requireChunk) {
        capture(DemandDescriptor.at(worldId, chunkX, chunkZ, status, requireChunk));
    }

    private synchronized void capture(DemandDescriptor descriptor) {
        captured++;
        Slot slot;
        if (size < CAP) {
            slot = ring[(head + size) % CAP];
            size++;
        } else {
            slot = ring[head];
            head = (head + 1) % CAP;
            dropped++;
        }
        slot.descriptor = descriptor;
        slot.completionSeen = false;
    }

    /** Marks the captures this completion answers: only the capture in a slot at this moment, so a
     * capture that arrives later did not wait on it and one the freeze took is not marked. */
    private synchronized void markCompleted(String worldId, String status) {
        completions++;
        String world = worldId == null ? "" : worldId;
        String state = status == null ? "" : status;
        for (int index = 0; index < size; index++) {
            Slot slot = ring[(head + index) % CAP];
            DemandDescriptor descriptor = slot.descriptor;
            if (descriptor != null && descriptor.worldId().equals(world)
                && descriptor.targetStatus().equals(state)) {
                slot.completionSeen = true;
            }
        }
    }

    /** Reads the captures of one freeze and takes them: a window belongs to the freeze that read it.
     * The generation of a capture's own world is read once here and never re-read into the column. */
    public synchronized List<Shadow> readFor(Freeze freeze, LiveWorldEpoch epochs) {
        windows++;
        FrozenDeclarations.FreezeKey key = freeze == null
            ? FrozenDeclarations.FreezeKey.NONE : freeze.key();
        long planSequence = freeze == null ? 0L : freeze.planSequence();
        long planGeneration = freeze == null ? 0L : freeze.planGeneration();
        List<Shadow> shadows = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            Slot slot = ring[(head + index) % CAP];
            DemandDescriptor descriptor = slot.descriptor;
            slot.descriptor = null;
            if (descriptor == null) {
                continue;
            }
            long epoch = epochs == null ? CommitLog.NO_WORLD_EPOCH
                : epochs.epochOf(descriptor.worldId());
            Shadow shadow = new Shadow(descriptor.worldId(), DOMAIN, keyOf(descriptor),
                descriptor.chunk(), descriptor.targetStatus(), descriptor.requireChunk(), epoch,
                planSequence, planGeneration, key, lateBeforeFreeze(slot.completionSeen));
            slot.completionSeen = false;
            count(shadow);
            shadows.add(shadow);
        }
        head = 0;
        size = 0;
        last = List.copyOf(shadows);
        return last;
    }

    /** The declarations one freeze hands to its intake: one per capture of the window that carries
     * every column a declaration carries, in the order the window gave them, and none at all when the
     * window holds a capture a completion marked - that refusal is the window's, so the captures it
     * did not mark are not handed over either. The key it declares is the capture's own node key,
     * which is the string the plan matches the candidate on.
     *
     * @param window the list the one read of that freeze returned
     * @return the declarations of that window, in its order
     * @throws IllegalStateException when the list is not the window the one read returned */
    public List<JobDeclaration> declarationsOf(List<Shadow> window) {
        requireTheWindowItRead(window);
        if (late(window)) {
            return List.of();
        }
        List<JobDeclaration> declarations = new ArrayList<>(window.size());
        for (Shadow shadow : window) {
            if (shadow != null && shadow.declared()) {
                declarations.add(declarationOf(shadow));
            }
        }
        return declarations;
    }

    /** The candidates of the window one read returned, for the merged list of the freeze that read
     * it: the same list the declarations above are derived from, and the same whole-window refusal.
     *
     * @param window the list the one read of that freeze returned
     * @return one candidate per handed capture of that window, in its order
     * @throws IllegalStateException when the list is not the window the one read returned */
    public List<FrozenDeclarations.Candidate> candidatesOf(List<Shadow> window) {
        requireTheWindowItRead(window);
        if (late(window)) {
            return List.of();
        }
        List<FrozenDeclarations.Candidate> candidates = new ArrayList<>(window.size());
        for (Shadow shadow : window) {
            if (shadow != null && shadow.declared()) {
                candidates.add(candidateOf(shadow));
            }
        }
        return candidates;
    }

    /** Whether a completion the freeze saw marked a capture of the window: the refusal is the
     * window's and is never a fact about one ask. */
    private static boolean late(List<Shadow> window) {
        for (Shadow shadow : window) {
            if (shadow != null && shadow.late()) {
                return true;
            }
        }
        return false;
    }

    /** One declaration per handed capture. It asks for no write right and names no site of its own:
     * what it adds to the plan is the node the candidate of that same capture is matched at. */
    private static JobDeclaration declarationOf(Shadow shadow) {
        return new JobDeclaration(shadow.nodeKey(), 0L, shadow.worldId(), shadow.domainId(), 0,
            List.of(), 0, shadow.nodeKey(), "world:" + shadow.worldId(), List.of(), List.of(),
            ShareClass.OTHER, JobDeclaration.SiteClass.PARALLEL, 0, 1, List.of());
    }

    /** One candidate per handed capture: the three names the declaration carries, the chunk and the
     * target state the call named, and no instance number - the freeze point mints that one and this
     * side never fills the column in. */
    private static FrozenDeclarations.Candidate candidateOf(Shadow shadow) {
        return new FrozenDeclarations.Candidate(shadow.worldId(), shadow.domainId(), shadow.nodeKey(),
            shadow.chunk(), shadow.targetStatus(), 0L, shadow.worldEpoch());
    }

    private void requireTheWindowItRead(List<Shadow> window) {
        if (window != last) {
            // An equal copy is not this freeze's window: two hand-offs that read two lists of one
            // freeze would declare the same node twice, and the seal would refuse the whole table.
            throw new IllegalStateException(
                "a hand-off derives from the window the one read returned");
        }
    }

    private void count(Shadow shadow) {
        if (shadow.state().equals(NOT_EXECUTABLE)) {
            unexecutable++;
        } else {
            notWired++;
        }
        if (shadow.late()) {
            lateRefusals++;
        }
        if (shadow.chunk() == null || shadow.targetStatus().isEmpty() || shadow.worldId().isEmpty()) {
            unshaped++;
        } else if (!CommitLog.liveWorldEpoch(shadow.worldEpoch())) {
            unlive++;
        }
    }

    public long windows() {
        return windows;
    }

    public long captured() {
        return captured;
    }

    public long dropped() {
        return dropped;
    }

    public long completions() {
        return completions;
    }

    public synchronized int held() {
        return size;
    }

    public long lastRead() {
        return last.size();
    }

    /** What the last freeze read, read-only and bounded by the ring. */
    public List<Shadow> lastCandidates() {
        return last;
    }

    public long unexecutable() {
        return unexecutable;
    }

    public long notWired() {
        return notWired;
    }

    public long lateRefusals() {
        return lateRefusals;
    }

    public long unshaped() {
        return unshaped;
    }

    public long unlive() {
        return unlive;
    }

    public synchronized void reset() {
        for (Slot slot : ring) {
            slot.descriptor = null;
            slot.completionSeen = false;
        }
        head = 0;
        size = 0;
        windows = 0L;
        captured = 0L;
        dropped = 0L;
        completions = 0L;
        last = List.of();
        unexecutable = 0L;
        notWired = 0L;
        lateRefusals = 0L;
        unshaped = 0L;
        unlive = 0L;
    }
}
