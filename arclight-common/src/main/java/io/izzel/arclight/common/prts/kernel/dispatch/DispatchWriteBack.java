/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.dispatch;

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

/**
 * The write-back leg of the merge: it settles what a worker computed at the commit point.
 *
 * <p>There are two settlements and the tier decides which one runs. Compute-only - the default - reads
 * the world back in the tick the frame belongs to and counts how many rows the host path itself
 * produced; it lands nothing, so the position, orientation and velocity of every entity stay with the
 * host and the domain cannot move the state it froze. Takeover - the opt-in tier - builds one payload
 * per batch out of the values the merge read and hands it to the intent channel under the identity of
 * that batch, so the order the channel freezes is the order the plan froze and the commit segment -
 * the only drainer of the channel, owned by the server thread - is what applies the values. A batch
 * that had to be redone on the tick thread is settled in the same position, so a fallback changes who
 * computed the values and never whether they are settled.</p>
 *
 * <p>Whichever settlement runs, it runs on the thread that owns the tick and inside the merge of the
 * tick the frame belongs to: no value a worker computed from one tick's view is ever written in a
 * later tick, and a result that is not ready falls back to the tick thread's own computation before
 * the merge commits the batch.</p>
 *
 * <p>The read back is the other half: at the next commit boundary the world is asked what it holds
 * for the rows the previous frame committed, and the answer is hashed with the same fold and the same
 * whitelist as the frame itself. That is what turns "the two arms agree in memory" into "the world
 * holds what the parallel arm computed", and it is also what a mismatch is reported against instead
 * of the tautology of re-hashing the same list.</p>
 *
 * <p>Every value the leg publishes is readable at zero: a pool that never started, a channel that
 * never drained and a world that was never read all render as numbers rather than as absent rows.</p>
 */
public final class DispatchWriteBack {

    /** What the read back of one frame found. */
    public record ReadBack(int rows, int gone, boolean equal) {

        /** @return a read back that had nothing to check */
        public static ReadBack empty() {
            return new ReadBack(0, 0, true);
        }
    }

    /** Prefix of the site identity every write-back intent carries. */
    public static final String SITE_PREFIX = "dispatch-batch";

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

    /**
     * Creates the leg.
     *
     * <p>The store is reached through two handles rather than through its type: the channel is the
     * layer below the dispatcher, the store of handed-over writes lives next to the write paths above
     * it, and a dispatcher that named that type would depend on a layer it is supposed to sit under.
     * The handles are bound once, by the module that owns both.</p>
     *
     * @param intents    the channel the payloads are frozen into
     * @param bind       stores a payload and answers the handle its intent carries
     * @param drop       forgets a payload that was never enqueued
     * @param worldEpoch answers the generation of a world, as the world lifecycle tracks it
     * @param readings   where the leg publishes
     * @param takeover   answers whether this settlement may land the values, read per merge so a
     *                   reload applies without a restart
     */
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

    /**
     * Settles one batch of the frozen order at the commit point.
     *
     * <p>This is the one call the merge makes: the tier decides whether the values become a write the
     * commit segment lands or a read back of the world that lands nothing. It runs in the merge of the
     * tick the frame belongs to either way, so the settlement never carries a value across a tick.</p>
     *
     * @param batch the batch the values belong to
     * @param rows  the committed rows of that batch, in the frozen order
     * @return whether the values were handed to the intent channel
     */
    public boolean settle(WorkBatch batch, List<StateHasher.Slice> rows) {
        if (takeover()) {
            enqueue(batch, rows);
            return true;
        }
        agree(rows);
        return false;
    }

    /** @return whether this settlement may land the values it was handed */
    public boolean takeover() {
        try {
            return takeover != null && takeover.getAsBoolean();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Settles one batch by reading the world back in the same tick and landing nothing.
     *
     * <p>Every row is compared with what the entity holds right now, bit for bit: a row the host
     * already holds is the host path's own result for this tick and is counted as produced by both
     * arms, and a row that differs is counted and stays with the host path. Neither branch calls a
     * setter, so this settlement cannot move an entity - which is what makes the compute-only tier
     * bit-identical with the host: the domain observes, it never owns the state.</p>
     *
     * <p>The read back is taken in the tick the frame belongs to and before the next tick's systems
     * observe anything, which is the boundary that keeps a stale value from ever reaching the world:
     * there is no value to reach it.</p>
     *
     * @param rows the committed rows of one batch, in the frozen order
     * @return how many rows agreed, stayed with the host path and were gone
     */
    public Settlement agree(List<StateHasher.Slice> rows) {
        long startedAt = System.nanoTime();
        String worldId = rows.isEmpty() ? "" : rows.get(0).worldId();
        String regionId = rows.isEmpty() ? "" : rows.get(0).regionId();
        ServerLevel level = LiveEntityAccess.level(worldId);
        int agreed = 0;
        int kept = 0;
        int gone = 0;
        if (level == null) {
            gone = rows.size();
        } else {
            for (StateHasher.Slice row : rows) {
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
        noteEntity(readings, worldId, regionId, nanos);
        return new Settlement(agreed, kept, gone);
    }

    /**
     * What one compute-only settlement found.
     *
     * @param agreed rows the host already held bit for bit
     * @param kept   rows that differed and stayed with the host path
     * @param gone   rows whose entity was no longer in its level
     */
    public record Settlement(int agreed, int kept, int gone) {
    }

    /**
     * Freezes one committed batch into the intent channel.
     *
     * <p>The channel assigns the order, so a batch refused at the depth limit leaves no gap behind
     * it and the cursor of the commit segment can never meet an order nobody queued. A refusal is
     * counted and the payload is forgotten: the values stay in the frame and in its hash, they are
     * simply not written to the world this tick.</p>
     *
     * @param batch the batch the values belong to
     * @param rows  the committed rows of that batch, in the frozen order
     * @return how many intents were accepted
     */
    public int enqueue(WorkBatch batch, List<StateHasher.Slice> rows) {
        if (rows.isEmpty()) {
            return 0;
        }
        WorkTask task = batch.task();
        String handle = bind.apply(PAYLOAD_PREFIX, new BatchWriteBack(rows, readings));
        WriteIntent draft = WriteIntent.draft(intents.nextIntentId(), task.worldId(), task.worldId(),
            DOMAIN_ID, 0L, worldEpoch.apply(task.worldId()).longValue(), handle, WAIT_POINT,
            SITE_PREFIX + ":" + task.batchId());
        IntentQueue.EnqueueResult result = intents.enqueue(draft);
        if (!result.accepted()) {
            drop.accept(handle);
            readings.noteWriteBackRefused();
            return 0;
        }
        readings.noteWriteBackEnqueued(rows.size());
        return 1;
    }

    /**
     * Asks the world what it holds for a frame the commit already reached.
     *
     * <p>Only the rows whose entity is still in its level take part in the comparison, and both sides
     * are built from that same surviving set, so the two hashes fold the same number of rows. The rows
     * that are gone are counted rather than compared: an entity that left the world between the merge
     * and the read back is a fact about the world, not a value the write-back got wrong.</p>
     *
     * @param committed the rows the frame committed
     * @param whitelist the fields the hash folds
     * @param domainId  the domain the frame belongs to
     * @param tickIndex the tick the read back belongs to
     * @return what the read back found
     */
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

    /** @return intents the leg froze into the channel since the last reset */
    public long enqueued() {
        return readings.writeBackEnqueued();
    }

    /**
     * Renders the last read back as one readable row.
     *
     * <p>The counts say whether the world agreed; this says what the world held. It is the row an
     * operator can compare with a position read back from the world by hand, entity by entity and
     * field by field, which is what a spot check of the equivalence needs.</p>
     *
     * @return one line naming the entity, what the frame committed and what the world held
     */
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

    /**
     * Records work the tick thread did for the entity domain.
     *
     * @param readings  where the leg publishes
     * @param worldId   the world the work belongs to
     * @param regionRef the region the work belongs to
     * @param nanos     the duration
     */
    public static void noteEntity(DispatchReadings readings, String worldId, String regionRef,
                                  long nanos) {
        readings.noteEntityMain(nanos);
        SelfTimers.note(SelfClass.ENTITY, worldId, regionRef, nanos);
    }

    /**
     * Records the work of reading the entity candidates of one tick.
     *
     * <p>The read side is part of the domain's cost on the tick thread in both arms: the serial arm
     * has to know what to integrate as much as the parallel arm has to know what to hand out, so a
     * comparison that left it out would compare two different jobs.</p>
     *
     * @param readings  where the leg publishes
     * @param worldId   the world the read belongs to
     * @param regionRef the region the read belongs to
     * @param nanos     the duration
     */
    public static void noteSnapshot(DispatchReadings readings, String worldId, String regionRef,
                                    long nanos) {
        readings.noteSnapshot(nanos);
        SelfTimers.note(SelfClass.ENTITY, worldId, regionRef, nanos);
    }

    /**
     * Records the work of the equivalence evidence itself.
     *
     * <p>The serial arm and the descent exist only to produce the witness, so their time lands in the
     * observation row and never in a class row; the design forbids observation self-cost from being
     * spread over the classes it observes.</p>
     *
     * @param readings where the leg publishes
     * @param worldId  the world the evidence belongs to
     * @param nanos    the duration
     */
    public static void noteEvidence(DispatchReadings readings, String worldId, long nanos) {
        readings.noteSerialArm(nanos);
        SelfTimers.note(SelfClass.OBSERVE, worldId, "dispatch-evidence", nanos);
    }
}
