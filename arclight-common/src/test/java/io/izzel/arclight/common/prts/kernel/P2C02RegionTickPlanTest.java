/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import io.izzel.arclight.common.prts.kernel.jobs.JobIntake;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The deterministic region-tick plan: random/fluid/block steps are grouped by region, the same
 * input always yields the same order, a batch never spans regions and a direct write stays inside
 * its batch's region. */
class P2C02RegionTickPlanTest {

    @Test
    void theSameInputInAnyOrderYieldsTheSamePlan() {
        List<RegionTickPlanner.Chunk> chunks = List.of(
            new RegionTickPlanner.Chunk("p2c02-world", "r-b", 4, 4, 9L),
            new RegionTickPlanner.Chunk("p2c02-world", "r-a", 1, 1, 7L),
            new RegionTickPlanner.Chunk("p2c02-world", "r-a", 0, 0, 3L),
            new RegionTickPlanner.Chunk("p2c02-world", "r-b", 5, 5, 8L));
        List<RegionTickPlanner.Chunk> reversed = new ArrayList<>(chunks);
        Collections.reverse(reversed);

        for (RegionTickPlanner.Kind kind : RegionTickPlanner.Kind.values()) {
            RegionTickPlanner.Plan first = RegionTickPlanner.plan(kind, chunks, 7L, 1L);
            RegionTickPlanner.Plan second = RegionTickPlanner.plan(kind, reversed, 7L, 1L);
            assertEquals(first.steps(), second.steps());
            assertEquals(first.batches(), second.batches());
            assertEquals(first.commitOrder(), first.replay());
        }
    }

    @Test
    void randomOrderIsRegionThenChunkThenSeed() {
        RegionTickPlanner.Plan plan = RegionTickPlanner.plan(RegionTickPlanner.Kind.RANDOM_TICK,
            List.of(new RegionTickPlanner.Chunk("w", "r", 1, 0, 5L),
                new RegionTickPlanner.Chunk("w", "r", 0, 0, 9L),
                new RegionTickPlanner.Chunk("w", "r", 0, 0, 2L)), 1L, 1L);

        List<Long> seeds = new ArrayList<>();
        for (RegionTickPlanner.Step step : plan.steps()) {
            seeds.add(step.seed());
        }
        assertEquals(List.of(2L, 9L, 5L), seeds);
    }

    @Test
    void fluidAndBlockWavesAreSegmentedByRegionAndNeverCrossABatch() {
        List<RegionTickPlanner.Chunk> chunks = List.of(
            new RegionTickPlanner.Chunk("w", "r-a", 0, 0, 1L),
            new RegionTickPlanner.Chunk("w", "r-a", 1, 0, 2L),
            new RegionTickPlanner.Chunk("w", "r-b", 9, 9, 3L));

        for (RegionTickPlanner.Kind kind : List.of(RegionTickPlanner.Kind.FLUID_UPDATE,
            RegionTickPlanner.Kind.BLOCK_UPDATE)) {
            RegionTickPlanner.Plan plan = RegionTickPlanner.plan(kind, chunks, 2L, 1L);
            assertEquals(2, plan.batches().size());
            assertEquals("r-a", plan.batches().get(0).regionId());
            assertEquals("r-b", plan.batches().get(1).regionId());
            assertEquals(2, plan.batches().get(0).steps().size());
            assertEquals(1, plan.batches().get(1).steps().size());
            for (RegionTickPlanner.Batch batch : plan.batches()) {
                for (RegionTickPlanner.Step step : batch.steps()) {
                    assertEquals(batch.regionId(), step.regionId());
                }
            }
        }
    }

    @Test
    void aDirectWriteIsOnlyAcceptedInsideTheBatchRegion() {
        RegionTickPlanner.Plan plan = RegionTickPlanner.plan(RegionTickPlanner.Kind.RANDOM_TICK,
            List.of(new RegionTickPlanner.Chunk("w", "r-a", 0, 0, 1L),
                new RegionTickPlanner.Chunk("w", "r-b", 1, 0, 2L)), 3L, 1L);
        RegionTickPlanner.Step a = plan.batches().get(0).steps().get(0);
        RegionTickPlanner.Step b = plan.batches().get(1).steps().get(0);

        assertTrue(plan.directWriteAllowed(a, "r-a"));
        assertFalse(plan.directWriteAllowed(a, "r-b"));
        assertFalse(plan.directWriteAllowed(b, "r-a"));
        assertFalse(plan.directWriteAllowed(null, "r-a"));
    }

    @Test
    void thePlanCanBeDeclaredThroughTheExistingJobIntake() {
        RegionTickPlanner.Plan plan = RegionTickPlanner.plan(RegionTickPlanner.Kind.FLUID_UPDATE,
            List.of(new RegionTickPlanner.Chunk("w", "r-a", 0, 0, 1L),
                new RegionTickPlanner.Chunk("w", "r-b", 1, 0, 2L)), 4L, 1L);
        JobIntake intake = new JobIntake(() -> 8);

        int accepted = RegionTickPlanner.declareInto(intake, plan);
        assertEquals(plan.batches().size(), accepted);
        assertEquals(plan.batches().size(), intake.depth());
        assertEquals(plan.batches().size(), intake.take().size());
        assertEquals(0, intake.depth());
    }

    @Test
    void anEmptyInputMakesAnEmptyPlanAndNoDeclaration() {
        RegionTickPlanner.Plan plan = RegionTickPlanner.plan(RegionTickPlanner.Kind.RANDOM_TICK,
            List.of(), 5L, 1L);
        JobIntake intake = new JobIntake(() -> 8);

        assertEquals(0, plan.steps().size());
        assertEquals(0, plan.batches().size());
        assertEquals(0, RegionTickPlanner.declareInto(intake, plan));
        assertEquals(0, intake.depth());
    }
}
