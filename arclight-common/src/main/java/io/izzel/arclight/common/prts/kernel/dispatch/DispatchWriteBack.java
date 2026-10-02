/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.dispatch;

import io.izzel.arclight.common.prts.kernel.arena.ArenaScratch;
import io.izzel.arclight.common.prts.kernel.config.KernelSettings;
import io.izzel.arclight.common.prts.kernel.diff.DomainHash;
import io.izzel.arclight.common.prts.kernel.diff.HashWhitelist;
import io.izzel.arclight.common.prts.kernel.diff.StateHasher;
import io.izzel.arclight.common.prts.kernel.intent.IntentQueue;
import io.izzel.arclight.common.prts.kernel.intent.WriteIntent;
import io.izzel.arclight.common.prts.kernel.meter.SelfClass;
import io.izzel.arclight.common.prts.kernel.meter.SelfTimers;
import io.izzel.arclight.common.prts.support.PrtsWorldWriteTaps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import io.izzel.arclight.common.prts.kernel.dispatch.WorkPlan.WorkBatch;
import io.izzel.arclight.common.prts.kernel.dispatch.WorkPlan.WorkTask;

/** There are two settlements and the tier decides which one runs. */
public final class DispatchWriteBack {

    /** What the read back of one frame found. */
    public record ReadBack(int rows, int gone, boolean equal) {

        public static ReadBack empty() {
            return new ReadBack(0, 0, true);
        }
    }

    /** How one batch was settled; only {@link #ACCEPTED} means it was handed to the channel. */
    public enum Settlement {

        COMPUTE_ONLY("compute-only"),
        ACCEPTED("accepted"),
        REFUSED_QUEUE_CAP("refused-queue-cap"),
        REFUSED_WORLD_EPOCH("refused-world-epoch"),
        REFUSED_PAYLOAD("refused-payload");

        private final String key;

        Settlement(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }

        public boolean landed() {
            return this == ACCEPTED;
        }
    }

    /** Prefix of the site identity every write-back intent carries. */
    public static final String SITE_PREFIX = "dispatch-batch";

    /** Most rows of one batch the settlement reads back from the world bit for bit. */
    public static final int SAMPLE_ROWS = 2;

    private static final long UNTRACKED_EPOCH = 0L;

    private static final String PAYLOAD_PREFIX = "dispatch";
    private static final String DOMAIN_ID = "entity-kinematics";
    private static final String WAIT_POINT = "xdomain";

    private final IntentQueue intents;
    private final BiFunction<String, PrtsWorldWriteTaps.DeferredWrite, String> bind;
    private final Consumer<String> drop;
    private final Function<String, Long> worldEpoch;
    private final DispatchReadings readings;
    private final BooleanSupplier takeover;
    private volatile String sample = "readback=none";

    /** The store is reached through two handles rather than through its type: the channel is the
     * layer below the dispatcher, the store of handed-over writes lives next to the write paths
     * above it, and a dispatcher that named that type would depend on a layer it is supposed to
     * sit under. */
    public DispatchWriteBack(IntentQueue intents,
                             BiFunction<String, PrtsWorldWriteTaps.DeferredWrite, String> bind,
                             Consumer<String> drop, Function<String, Long> worldEpoch,
                             DispatchReadings readings, BooleanSupplier takeover) {
        this.intents = intents;
        this.bind = bind;
        this.drop = drop;
        this.worldEpoch = worldEpoch;
        this.readings = readings;
        this.takeover = takeover;
    }

    /** Settles one batch at the commit point: the tier decides whether the values become a write
     * the commit segment lands or a read back that lands nothing. */
    public Settlement settle(WorkBatch batch, List<StateHasher.Slice> rows) {
        if (takeover()) {
            return enqueue(batch, rows);
        }
        sampleReadBack(batch, rows);
        return Settlement.COMPUTE_ONLY;
    }

    public boolean takeover() {
        try {
            return takeover != null && takeover.getAsBoolean();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private ReadBackSample sampleReadBack(WorkBatch batch, List<StateHasher.Slice> rows) {
        long startedAt = System.nanoTime();
        int count = rows.size();
        String worldId = rows.isEmpty() ? "" : rows.get(0).worldId();
        String regionId = rows.isEmpty() ? "" : rows.get(0).regionId();
        ServerLevel level = LiveEntityAccess.level(worldId);
        int agreed = 0;
        int kept = 0;
        int gone = 0;
        if (level == null) {
            gone = count;
        } else {
            for (int sample = 0; sample < SAMPLE_ROWS && sample < count; sample++) {
                StateHasher.Slice row = rows.get(sample == 0 ? 0 : spread(batch, count));
                Entity entity = LiveEntityAccess.entity(level, (int) row.entitySeq());
                if (entity == null) {
                    gone++;
                    continue;
                }
                if (LiveEntityAccess.identical(entity, row)) {
                    agreed++;
                } else {
                    kept++;
                }
            }
        }
        long nanos = System.nanoTime() - startedAt;
        readings.noteWriteBackIdentity(agreed, kept);
        readings.noteReadBack(agreed, gone, kept == 0);
        if (kept > 0) {
            readings.noteReadBackKept(kept);
        }
        noteVerify(readings, worldId, regionId, nanos);
        return new ReadBackSample(agreed, kept, gone);
    }

    /** Both sides of the comparison are folds over the values of the batch, so the verdict is one
     * comparison per batch and no entity is looked up and no object is built while it is taken. */
    public boolean verify(WorkBatch batch, ArenaScratch slot, ArenaScratch reference) {
        long startedAt = System.nanoTime();
        int rows = Math.min(slot.filled(), reference.filled());
        boolean trusted = slot.filled() == reference.filled()
            && EntityIntegrator.foldRange(slot, rows) == EntityIntegrator.foldRange(reference, rows);
        if (trusted && rows > 0) {
            trusted = sameRow(slot, reference, 0)
                && (rows < 2 || sameRow(slot, reference, spread(batch, rows)));
        }
        long nanos = System.nanoTime() - startedAt;
        readings.noteVerifyRows(rows);
        WorkTask task = batch.task();
        noteVerify(readings, task.worldId(), task.regionId(), nanos);
        if (!trusted) {
            readings.noteVerifyMismatch();
        }
        return trusted;
    }

    private static boolean sameRow(ArenaScratch left, ArenaScratch right, int index) {
        return EntityIntegrator.foldRow(left, index, 0L)
            == EntityIntegrator.foldRow(right, index, 0L);
    }

    private static int spread(WorkBatch batch, int rows) {
        return (int) Math.floorMod(batch.batchId(), (long) rows);
    }

    /** What one compute-only read back found. */
    public record ReadBackSample(int agreed, int kept, int gone) {
    }

    /** Freezes one committed batch into the intent channel. The channel assigns the order, so a
     * batch refused at the depth limit leaves no gap behind it and the cursor of the commit
     * segment can never meet an order nobody queued. */
    public Settlement enqueue(WorkBatch batch, List<StateHasher.Slice> rows) {
        if (rows.isEmpty()) {
            return Settlement.REFUSED_PAYLOAD;
        }
        WorkTask task = batch.task();
        long frozenEpoch = task.worldEpoch();
        if (currentEpoch(task.worldId()) != frozenEpoch) {
            readings.noteWriteBackStale();
            return Settlement.REFUSED_WORLD_EPOCH;
        }
        String handle = bind.apply(PAYLOAD_PREFIX, new BatchWriteBack(rows, readings));
        WriteIntent draft = WriteIntent.draft(intents.nextIntentId(), task.worldId(), task.worldId(),
            DOMAIN_ID, 0L, frozenEpoch, handle, WAIT_POINT,
            SITE_PREFIX + ":" + task.batchId());
        IntentQueue.EnqueueResult result = intents.enqueue(draft);
        if (!result.accepted()) {
            drop.accept(handle);
            readings.noteWriteBackRefused();
            return Settlement.REFUSED_QUEUE_CAP;
        }
        readings.noteWriteBackEnqueued(rows.size());
        return Settlement.ACCEPTED;
    }

    private long currentEpoch(String worldId) {
        Long epoch = worldEpoch.apply(worldId);
        return epoch == null ? UNTRACKED_EPOCH : epoch;
    }

    /** Asks the world what it holds for a frame the commit already reached. Only the rows whose
     * entity is still in its level take part in the comparison, and both sides are built from that
     * same surviving set, so the two hashes fold the same number of rows. */
    public ReadBack readBack(List<StateHasher.Slice> committed, HashWhitelist whitelist,
                             String domainId, long tickIndex) {
        if (committed.isEmpty()) {
            return ReadBack.empty();
        }
        long startedAt = System.nanoTime();
        List<StateHasher.Slice> expected = new ArrayList<>(committed.size());
        List<StateHasher.Slice> found = new ArrayList<>(committed.size());
        boolean identicalOnly = KernelSettings.dispatchIdenticalOnly();
        int gone = 0;
        int kept = 0;
        for (StateHasher.Slice row : committed) {
            ServerLevel level = LiveEntityAccess.level(row.worldId());
            Entity entity = LiveEntityAccess.entity(level, (int) row.entitySeq());
            if (entity == null) {
                gone++;
                continue;
            }
            if (identicalOnly && !LiveEntityAccess.identical(entity, row)) {
                // The takeover boundary kept this row with the host path, so the world is not
                // asked to agree with a value the leg deliberately did not land.
                kept++;
                continue;
            }
            expected.add(row);
            found.add(LiveEntityAccess.read(entity, row));
        }
        boolean equal = expected.isEmpty() || hash(found, whitelist, domainId, tickIndex)
            == hash(expected, whitelist, domainId, tickIndex);
        long nanos = System.nanoTime() - startedAt;
        readings.noteReadBack(found.size(), gone, equal);
        if (identicalOnly) {
            readings.noteReadBackKept(kept);
        }
        SelfTimers.note(SelfClass.OBSERVE, committed.get(0).worldId(), "dispatch-readback", nanos);
        if (!found.isEmpty()) {
            sample = render(found.get(0), expected.get(0), found.size(), gone, equal);
        }
        return new ReadBack(found.size(), gone, equal);
    }

    public long enqueued() {
        return readings.writeBackEnqueued();
    }

    /** Renders the last read back as one readable row. The counts say whether the world agreed;
     * this says what the world held. */
    public String sampleLine() {
        return sample;
    }

    private static String render(StateHasher.Slice found, StateHasher.Slice expected, int rows,
                                 int gone, boolean equal) {
        StringBuilder builder = new StringBuilder("[PRTS] dispatch-readback: ");
        builder.append("world=").append(found.worldId());
        builder.append(" entity=").append(found.entitySeq());
        builder.append(" batch=").append(found.batchId());
        builder.append(" rows=").append(rows);
        builder.append(" gone=").append(gone);
        builder.append(" equal=").append(equal ? 1 : 0);
        builder.append(" committed=").append(values(expected));
        builder.append(" world_values=").append(values(found));
        return builder.toString();
    }

    private static String values(StateHasher.Slice slice) {
        return String.format(Locale.ROOT, "(%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f)", slice.x(),
            slice.y(), slice.z(), slice.yaw(), slice.pitch(), slice.velX(), slice.velY(),
            slice.velZ());
    }

    private static long hash(List<StateHasher.Slice> slices, HashWhitelist whitelist,
                             String domainId, long tickIndex) {
        DomainHash hash = StateHasher.hash(domainId, tickIndex, slices, whitelist);
        return hash.comparable() ? hash.value() : 0L;
    }

    /** Records work the tick thread did for the entity domain by recomputing a batch itself. */
    public static void noteRedo(DispatchReadings readings, String worldId, String regionRef,
                                long nanos) {
        readings.noteRedo(nanos);
        SelfTimers.note(SelfClass.ENTITY, worldId, regionRef, nanos);
    }

    /** Records the work of checking what a worker answered. The check is main-thread work of the
     * entity domain - it reads the same view the worker read and samples the world - so it lands
     * in the entity row and in the check row, never in the row of the observation's own evidence.
     */
    public static void noteVerify(DispatchReadings readings, String worldId, String regionRef,
                                  long nanos) {
        readings.noteVerify(nanos);
        SelfTimers.note(SelfClass.ENTITY, worldId, regionRef, nanos);
    }

    /** The read side is part of the domain's cost on the tick thread in both arms: the serial arm
     * has to know what to integrate as much as the parallel arm has to know what to hand out, so a
     * comparison that left it out would compare two different jobs. */
    public static void noteSnapshot(DispatchReadings readings, String worldId, String regionRef,
                                    long nanos) {
        readings.noteSnapshot(nanos);
        SelfTimers.note(SelfClass.ENTITY, worldId, regionRef, nanos);
    }

    /** Records the work of the equivalence evidence itself. The serial arm and the descent exist
     * only to produce the witness, so their time lands in the observation row and never in a class
     * row; the design forbids observation self-cost from being spread over the classes it
     * observes. */
    public static void noteEvidence(DispatchReadings readings, String worldId, long nanos) {
        readings.noteSerialArm(nanos);
        SelfTimers.note(SelfClass.OBSERVE, worldId, "dispatch-evidence", nanos);
    }
}
