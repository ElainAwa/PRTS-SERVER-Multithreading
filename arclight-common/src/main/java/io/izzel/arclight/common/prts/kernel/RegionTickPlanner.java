/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import io.izzel.arclight.common.prts.kernel.jobs.JobDeclaration;
import io.izzel.arclight.common.prts.kernel.jobs.JobIntake;
import io.izzel.arclight.common.prts.kernel.shares.ShareClass;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** The offline deterministic plan for the three region-tick kinds. The random order is region,
 * then chunk, then seed; the fluid and block-update waves are segmented by region. A batch never
 * spans two regions, and a direct write is only accepted inside the batch's own region. The plan
 * is an observation shape and is not wired into the server tick. */
public final class RegionTickPlanner {

    /** The three region-tick work kinds. */
    public enum Kind {
        RANDOM_TICK,
        FLUID_UPDATE,
        BLOCK_UPDATE
    }

    /** One chunk coordinate of one region, with the random seed it carries. */
    public record Chunk(String worldId, String regionId, int x, int z, long seed) {
        public Chunk {
            if (worldId == null || worldId.isEmpty() || regionId == null || regionId.isEmpty()) {
                throw new IllegalArgumentException("a region tick chunk needs a world and a region");
            }
        }
    }

    /** One deterministic step: the kind, region, chunk key, seed, wave and global order. */
    public record Step(Kind kind, String worldId, String regionId, int x, int z, long seed,
                       int wave, long sequence) {
    }

    /** One batch: exactly one world and one region, with a contiguous run of steps. */
    public record Batch(Kind kind, String worldId, String regionId, int wave, List<Step> steps) {
        public Batch {
            steps = List.copyOf(steps);
            if (worldId == null || worldId.isEmpty() || regionId == null || regionId.isEmpty()
                || steps.isEmpty()) {
                throw new IllegalArgumentException("a region tick batch needs a world, region and steps");
            }
        }
    }

    /** The frozen plan of one tick: batches in order and the one ordered step list. */
    public record Plan(Kind kind, long tickIndex, long planSequence, List<Batch> batches,
                       List<Step> steps) {
        public Plan {
            batches = List.copyOf(batches);
            steps = List.copyOf(steps);
        }

        public List<Step> commitOrder() {
            return List.copyOf(steps);
        }

        public List<Step> replay() {
            return List.copyOf(steps);
        }

        public boolean directWriteAllowed(Step step, String targetRegion) {
            return step != null && targetRegion != null && targetRegion.equals(step.regionId());
        }
    }

    private RegionTickPlanner() {
    }

    /** Plans one kind of region tick. The input order never changes the result. */
    public static Plan plan(Kind kind, List<Chunk> chunks, long tickIndex, long planSequence) {
        if (kind == null) {
            throw new IllegalArgumentException("a plan needs a kind");
        }
        List<Chunk> ordered = new ArrayList<>(chunks == null ? List.of() : chunks);
        ordered.sort(Comparator.comparing(Chunk::regionId)
            .thenComparing(Chunk::worldId)
            .thenComparingInt(Chunk::x)
            .thenComparingInt(Chunk::z)
            .thenComparingLong(Chunk::seed));
        List<Batch> batches = new ArrayList<>();
        List<Step> steps = new ArrayList<>(ordered.size());
        int wave = 0;
        long sequence = 0L;
        int at = 0;
        while (at < ordered.size()) {
            Chunk first = ordered.get(at);
            int end = at + 1;
            while (end < ordered.size()
                && ordered.get(end).regionId().equals(first.regionId())
                && ordered.get(end).worldId().equals(first.worldId())) {
                end++;
            }
            List<Step> batchSteps = new ArrayList<>(end - at);
            for (int index = at; index < end; index++) {
                Chunk chunk = ordered.get(index);
                batchSteps.add(new Step(kind, chunk.worldId(), chunk.regionId(), chunk.x(),
                    chunk.z(), chunk.seed(), kind == Kind.RANDOM_TICK ? 0 : wave, sequence++));
            }
            steps.addAll(batchSteps);
            batches.add(new Batch(kind, first.worldId(), first.regionId(),
                kind == Kind.RANDOM_TICK ? 0 : wave, batchSteps));
            if (kind != Kind.RANDOM_TICK) {
                wave++;
            }
            at = end;
        }
        return new Plan(kind, tickIndex, planSequence, batches, steps);
    }

    /** Declares the frozen batches through the existing JobIntake shape; returns how many fit. */
    public static int declareInto(JobIntake intake, Plan plan) {
        if (intake == null || plan == null) {
            throw new IllegalArgumentException("a declaration needs an intake and a plan");
        }
        int accepted = 0;
        long handle = 1L;
        for (Batch batch : plan.batches()) {
            JobDeclaration declaration = new JobDeclaration(
                "regiontick/" + batch.worldId() + "/" + batch.regionId() + "/"
                    + batch.kind().name().toLowerCase(java.util.Locale.ROOT) + "/" + batch.wave(),
                handle++, batch.worldId(), "regiontick", 0, List.of(), batch.wave(),
                "regiontick/" + batch.worldId() + "/" + batch.regionId(),
                "regiontick." + batch.kind().name().toLowerCase(java.util.Locale.ROOT),
                List.of(new JobDeclaration.DomainRef(batch.worldId(), "regiontick", 0)),
                List.of(new JobDeclaration.DomainRef(batch.worldId(), "regiontick", 0)),
                ShareClass.REGIONTICK, JobDeclaration.SiteClass.PARALLEL, batch.kind().ordinal(),
                batch.steps().size());
            if (intake.submit(declaration).accepted()) {
                accepted++;
            }
        }
        return accepted;
    }
}
