/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import io.izzel.arclight.common.prts.kernel.commit.CommitLog;
import io.izzel.arclight.common.prts.kernel.commit.DeclarationInstanceAllocator;
import io.izzel.arclight.common.prts.kernel.commit.FrozenDeclarations;
import io.izzel.arclight.common.prts.kernel.commit.IdentityWitness;
import io.izzel.arclight.common.prts.kernel.commit.RangeSealTable;
import io.izzel.arclight.common.prts.kernel.commit.ReplayComparator;
import io.izzel.arclight.common.prts.kernel.jobs.DemandShadowCandidates;
import io.izzel.arclight.common.prts.kernel.jobs.PipelineBatchLedger;
import io.izzel.arclight.common.prts.kernel.jobs.PipelineRoundJobs;
import io.izzel.arclight.common.prts.kernel.plan.TickPlan;
import io.izzel.arclight.common.prts.kernel.plan.TickPlanStore;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The seal factory refuses a batch whose plan the declarations in force do not belong to: the
 * real ledger cuts the batch, the real plan store answers the seat and the real table decides the
 * publish. The declaration table is moved by the case; the freeze key is the real value object. */
class RangeSealFreezePlanAlignmentTest {

    private static final String WORLD = "minecraft:overworld";
    private static final String MAILBOX = "worldgen";
    private static final String DOMAIN = "chunk-pipeline";
    private static final long TICK = 100L;
    private static final long PLAN = 7L;
    private static final long CELL = ChunkPos.asLong(3, -7);
    private static final long EPOCH = 5L;
    private static final String LIFECYCLE = "host/1/0";
    private static final FrozenDeclarations.FreezeKey FREEZE =
        new FrozenDeclarations.FreezeKey(LIFECYCLE, 1L);

    private PipelineBatchLedger ledger;
    private TickPlanStore plans;
    private RangeSealTable table;
    private DeclarationInstanceAllocator instances;
    private AtomicReference<FrozenDeclarations> declared;
    private AtomicInteger reads;
    private PipelineRangeSeals factory;

    @BeforeEach
    void setUp() {
        ledger = new PipelineBatchLedger(64, 1000);
        plans = new TickPlanStore(8);
        table = new RangeSealTable(16);
        instances = new DeclarationInstanceAllocator();
        declared = new AtomicReference<>();
        reads = new AtomicInteger();
        factory = new PipelineRangeSeals(ledger, table, plans, instances::mintOne, world -> EPOCH,
            () -> {
                reads.incrementAndGet();
                return declared.get();
            });
    }

    /** One batch the case may seal: the task is put in the running state the factory checks. */
    private record Presented(PipelineBatchLedger.TrackedTask task,
                             PipelineBatchLedger.BatchId batch, String nodeKey) {
    }

    private Presented prepare(long plan, String... earlierNodes) {
        PipelineBatchLedger.TrackedTask task = ledger.mint(new Object(), WORLD, MAILBOX, CELL, TICK);
        ledger.enqueued(task);
        PipelineBatchLedger.BatchId batch = ledger.freeze(WORLD, MAILBOX, plan, TICK);
        assertNotNull(batch);
        List<TickPlan.CommitStep> steps = new ArrayList<>();
        int position = 0;
        for (String node : earlierNodes) {
            steps.add(new TickPlan.CommitStep(position++, WORLD, DOMAIN, node, 1L, 0, 1, false));
        }
        String nodeKey = PipelineRoundJobs.batchKeyOf(batch);
        steps.add(new TickPlan.CommitStep(position, WORLD, DOMAIN, nodeKey, 1L, 0, 1, false));
        plans.publish(new TickPlan(TICK, plan, 1L, List.of(WORLD), null, List.of(), steps, null,
            List.of(), 0, 0, 0L, null));
        return new Presented(task, batch, nodeKey);
    }

    @SuppressWarnings("unchecked")
    private static void setRunning(PipelineBatchLedger.TrackedTask task,
                                   PipelineBatchLedger.BatchId batch) {
        try {
            Field field = PipelineBatchLedger.TrackedTask.class.getDeclaredField("state");
            field.setAccessible(true);
            ((AtomicReference<PipelineBatchLedger.TaskState>) field.get(task))
                .set(new PipelineBatchLedger.Running(batch));
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static DemandShadowCandidates.TaskStatus statusOf(PipelineBatchLedger.TrackedTask task,
                                                              PipelineBatchLedger.BatchId batch) {
        PipelineBatchLedger.TaskId id = task.id();
        return new DemandShadowCandidates.TaskStatus("full", new DemandShadowCandidates.TaskStamp(
            id.ordinal(), id.worldId(), id.mailbox(), id.cell(), id.mintTick(),
            batch.freezeOrdinal(), batch.planSequence(), 77L));
    }

    private FrozenDeclarations.RangeSeal present(Presented prepared) {
        setRunning(prepared.task(), prepared.batch());
        return factory.seal(prepared.task(), statusOf(prepared.task(), prepared.batch()),
            factory.generation());
    }

    private static FrozenDeclarations table(long plan, FrozenDeclarations.Entry... entries) {
        FrozenDeclarations.Sealed sealed = FrozenDeclarations.of(FREEZE, plan, List.of(entries));
        assertTrue(sealed.sealed(), () -> "fixture table refused: " + sealed.refusal());
        return sealed.declarations();
    }

    @Test
    void aDeclarationTableOfTheSamePlanPublishesTheSealAndIsReadOnce() {
        Presented prepared = prepare(PLAN);
        declared.set(table(PLAN));

        FrozenDeclarations.RangeSeal seal = present(prepared);

        assertNotNull(seal);
        assertEquals(PLAN, seal.planSequence());
        assertEquals(FREEZE, seal.freeze());
        assertEquals(1, table.size());
        assertEquals(1L, factory.seals());
        assertEquals(1, reads.get(), "the table is read once and the snapshot is reused");
    }

    @Test
    void aDeclarationTableOfAnotherPlanRefusesTheSealBeforeTheMint() {
        Presented prepared = prepare(PLAN);
        declared.set(table(PLAN - 1L));
        long before = instances.mintOne();

        assertNull(present(prepared), "the freeze in force belongs to another plan");
        assertEquals(0, table.size());
        assertTrue(factory.refused() >= 1L);
        assertEquals(before + 1L, instances.mintOne(), "no instance number was spent");
        assertEquals(1, reads.get());
    }

    @Test
    void aMissingDeclarationTableRefusesTheSeal() {
        Presented prepared = prepare(PLAN);
        long before = instances.mintOne();

        assertNull(present(prepared));
        assertEquals(0, table.size());
        assertEquals(before + 1L, instances.mintOne());
        assertEquals(1, reads.get());
    }

    @Test
    void anUndeclaredFreezeTableRefusesTheSeal() {
        Presented prepared = prepare(PLAN);
        declared.set(FrozenDeclarations.none());
        long before = instances.mintOne();

        assertNull(present(prepared), "a table no freeze sealed carries no freeze to publish under");
        assertEquals(0, table.size());
        assertEquals(before + 1L, instances.mintOne());
    }

    @Test
    void anEntryThatDisagreesWithTheSealRefusesItAndNoneOfTheTwoReadsIsSkipped() {
        Presented prepared = prepare(PLAN);
        int role = 1;
        FrozenDeclarations.Seat seat = new FrozenDeclarations.Seat(WORLD, DOMAIN, PLAN, 0);
        FrozenDeclarations.Entry disagreeing = new FrozenDeclarations.Entry(seat, prepared.nodeKey(),
            role, CELL, "features", 41L, EPOCH);
        declared.set(table(PLAN, disagreeing));
        long before = instances.mintOne();

        assertNull(present(prepared), "the sealed entry and the status disagree");
        assertEquals(0, table.size());
        assertEquals(before + 1L, instances.mintOne());
        assertEquals(1, reads.get());

        FrozenDeclarations.Entry agreeing = new FrozenDeclarations.Entry(seat, prepared.nodeKey(),
            role, CELL, "full", 41L, EPOCH);
        declared.set(table(PLAN, agreeing));
        FrozenDeclarations.RangeSeal seal = present(prepared);
        assertNotNull(seal, "the same table with an agreeing entry publishes the seal");
        assertEquals(role, seal.role());
    }

    @Test
    void theOldRecordShapesAndTheFourPartEvidenceAreUnchanged() {
        assertEquals(7, FrozenDeclarations.Entry.class.getRecordComponents().length,
            "the entry keeps its seven columns");
        String golden = "I17:world-a14:chunk-pipeline3:a/0#2;#64;4:full";
        IdentityWitness.LogicalKey key = new IdentityWitness.LogicalKey("world-a", "chunk-pipeline",
            "a/0", 2, 64L, "full");
        assertEquals(golden, key.canonical());
        assertFalse(key.ranged());
        assertEquals(key, IdentityWitness.parse(golden));
        IdentityWitness.LogicalKey ranged = IdentityWitness.LogicalKey.ranged("world-a",
            "chunk-pipeline", "a/0", 2, "space", "status");
        assertTrue(ranged.canonical().startsWith("I2"));
        assertTrue(ranged.ranged());

        CommitLog.Entry.Step step = new CommitLog.Entry.Step(7L, 4, 64L);
        CommitLog.SchemaReplay replay = new CommitLog.SchemaReplay(CommitLog.SCHEMA_LEGACY,
            List.of(step), List.of(), 0);
        FrozenDeclarations.Sealed sealed = FrozenDeclarations.of(FREEZE, PLAN,
            List.of(new FrozenDeclarations.Entry(new FrozenDeclarations.Seat("world-a",
                "chunk-pipeline", 7L, 4), "a/0", 2, 64L, "full", 41L, 5L)));
        assertTrue(sealed.sealed());
        ReplayComparator.Verdict verdict = new ReplayComparator().compare(
            new ReplayComparator.Evidence("commit-1", ReplayComparator.recordsDigest(replay),
                sealed.declarations(), replay),
            new ReplayComparator.Evidence("commit-2", ReplayComparator.recordsDigest(replay),
                sealed.declarations(), replay));
        assertTrue(verdict.equal(), "the four-part evidence still compares an entry run");
    }
}
