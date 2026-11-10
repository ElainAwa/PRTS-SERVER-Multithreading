/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.ownership;

import io.izzel.arclight.common.prts.kernel.domain.entity.ownership.replica.TickState;
import net.minecraft.server.MinecraftServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Test-only plan seam: descriptor rows drive the real lease/publish/commit adapter. */
class AiPlanSeamTest {

    private static MobAiTickModel model(boolean valid) {
        MobAiTickModel model = new MobAiTickModel();
        return model;
    }

    private static TickState state(MobAiTickModel model, boolean valid) {
        TickState state = new TickState();
        state.entityId = 1;
        model.captureReadSet(new MobAiTickModel.ReadSet(9L, List.of("block"), List.of("entity"),
            List.of("poi"), List.of("sensor"), List.of("stroll"), "stroll", valid), state);
        return state;
    }

    private static EntityTickOwnership.TestRow row(int id, MobAiTickModel model, TickState state) {
        return new EntityTickOwnership.TestRow(id, 2L, 3L, 4, true, true, (byte) 0,
            0.0, 0.0, 0.0, 5L, id, model, state);
    }

    @AfterEach
    void clear() {
        EntityTickOwnership.clearTestPlanProviderForTests();
    }

    @Test
    void aiRowsAreClaimedAndHostCommitsEachOnce() throws Exception {
        MobAiTickModel first = model(true);
        MobAiTickModel second = model(true);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            EntityTickOwnership.installTestPlanProviderForTests(
                () -> List.of(row(11, first, state(first, true)), row(12, second, state(second, true))),
                new EntityTickOwnership.TestPlanConfig() {
                    @Override public Executor executor() { return pool; }
                    @Override public int chunks() { return 2; }
                });
            EntityTickOwnership.onServerTickPre((MinecraftServer) null);
            EntityTickOwnership.TestPlan plan = EntityTickOwnership.currentTestPlan();
            assertEquals(2, plan.rows());
            waitSettled(plan);
            assertEquals(EntityTickOwnership.SKIP_HOST_TICK, plan.commitOnce(11));
            assertEquals(EntityTickOwnership.SKIP_HOST_TICK, plan.commitOnce(12));
            assertEquals(EntityTickOwnership.RUN_HOST_TICK, plan.commitOnce(11));
            assertEquals(1L, first.appliedCount());
            assertEquals(1L, second.appliedCount());
            assertEquals(1L, first.computeRows());
            assertEquals(1L, second.computeRows());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void invalidReadSetFallsBackOnceWithoutApply() throws Exception {
        MobAiTickModel model = model(false);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            EntityTickOwnership.installTestPlanProviderForTests(
                () -> List.of(row(21, model, state(model, false))),
                new EntityTickOwnership.TestPlanConfig() {
                    @Override public Executor executor() { return pool; }
                    @Override public int chunks() { return 1; }
                });
            EntityTickOwnership.onServerTickPre((MinecraftServer) null);
            EntityTickOwnership.TestPlan plan = EntityTickOwnership.currentTestPlan();
            waitSettled(plan);
            assertEquals(EntityTickOwnership.RUN_HOST_TICK, plan.commitOnce(21));
            assertEquals(0L, model.computeRows());
            assertEquals(0L, model.appliedCount());
            assertEquals(EntityTickOwnership.RUN_HOST_TICK, plan.fallbackOnce(21));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void rejectedExecutorFallsBackWithoutApply() {
        MobAiTickModel model = model(true);
        EntityTickOwnership.installTestPlanProviderForTests(
            () -> List.of(row(31, model, state(model, true))),
            new EntityTickOwnership.TestPlanConfig() {
                @Override public Executor executor() { return command -> { throw new RejectedExecutionException("closed"); }; }
                @Override public int chunks() { return 1; }
            });
        EntityTickOwnership.onServerTickPre((MinecraftServer) null);
        EntityTickOwnership.TestPlan plan = EntityTickOwnership.currentTestPlan();
        assertEquals(EntityTickOwnership.RUN_HOST_TICK, plan.commitOnce(31));
        assertEquals(0L, model.appliedCount());
    }

    @Test
    void lateAnswerAfterCloseIsNeverApplied() {
        MobAiTickModel model = model(true);
        List<Runnable> tasks = new ArrayList<>();
        EntityTickOwnership.installTestPlanProviderForTests(
            () -> List.of(row(41, model, state(model, true))),
            new EntityTickOwnership.TestPlanConfig() {
                @Override public Executor executor() { return tasks::add; }
                @Override public int chunks() { return 1; }
            });
        EntityTickOwnership.onServerTickPre((MinecraftServer) null);
        EntityTickOwnership.TestPlan plan = EntityTickOwnership.currentTestPlan();
        plan.close();
        tasks.forEach(Runnable::run);
        assertEquals(EntityTickOwnership.RUN_HOST_TICK, plan.commitOnce(41));
        assertEquals(0L, model.appliedCount());
    }

    @Test
    void claimedRowIsExcludedFromSecondHostCommit() throws Exception {
        MobAiTickModel model = model(true);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            EntityTickOwnership.installTestPlanProviderForTests(
                () -> List.of(row(51, model, state(model, true))),
                new EntityTickOwnership.TestPlanConfig() {
                    @Override public Executor executor() { return pool; }
                    @Override public int chunks() { return 1; }
                });
            EntityTickOwnership.onServerTickPre((MinecraftServer) null);
            EntityTickOwnership.TestPlan plan = EntityTickOwnership.currentTestPlan();
            waitSettled(plan);
            assertEquals(EntityTickOwnership.SKIP_HOST_TICK, plan.commitOnce(51));
            assertEquals(EntityTickOwnership.RUN_HOST_TICK, plan.commitOnce(51));
            assertEquals(1L, model.appliedCount());
        } finally {
            pool.shutdownNow();
        }
    }

    private static void waitSettled(EntityTickOwnership.TestPlan plan) throws Exception {
        for (int i = 0; i < 400; i++) {
            boolean all = true;
            for (int index = 0; index < plan.rows(); index++) {
                int state = plan.lease().state(index);
                if (state == OwnershipLease.PENDING) {
                    all = false;
                    break;
                }
            }
            if (all) {
                return;
            }
            Thread.sleep(2L);
        }
        throw new AssertionError("lease did not settle");
    }
}
