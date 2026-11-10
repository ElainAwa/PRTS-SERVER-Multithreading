/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.ownership;

import io.izzel.arclight.common.prts.kernel.domain.entity.ownership.replica.TickState;
import io.izzel.arclight.common.prts.kernel.domain.entity.ownership.replica.WholeTickModel;
import net.minecraft.world.entity.Entity;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/** Test-only minimal AI whole-tick scaffold. The read-set and answer live on the row state. */
final class MobAiTickModel implements WholeTickModel {

    /** A capture-driven frozen row: string fields copied from the plan capture JSON. */
    record CaptureRow(Map<String, String> fields) {
        CaptureRow {
            fields = Map.copyOf(fields);
        }
    }

    /** The host-side frozen view. Any mutation/unknown behavior invalidates the row. */
    record ReadSet(long fingerprint, List<String> blocks, List<String> entities, List<String> pois,
                   List<String> sensors, List<String> behaviors, String navigation, boolean valid) {
        ReadSet {
            blocks = List.copyOf(blocks);
            entities = List.copyOf(entities);
            pois = List.copyOf(pois);
            sensors = List.copyOf(sensors);
            behaviors = List.copyOf(behaviors);
        }
    }

    private final LongAdder computeNanos = new LongAdder();
    private final LongAdder computeRows = new LongAdder();
    private final LongAdder applied = new LongAdder();
    private final LongAdder refusals = new LongAdder();

    void captureReadSet(ReadSet readSet, TickState state) {
        state.modelState = readSet;
        state.modelAnswer = null;
    }

    void captureCaptureRow(CaptureRow row, TickState state) {
        state.modelState = row;
        state.modelAnswer = null;
    }

    void invalidate(TickState state) {
        Object current = state.modelState;
        if (current instanceof ReadSet readSet) {
            state.modelState = new ReadSet(readSet.fingerprint(), readSet.blocks(), readSet.entities(),
                readSet.pois(), readSet.sensors(), readSet.behaviors(), readSet.navigation(), false);
        }
    }

    @Override
    public String name() {
        return "mob_ai_test";
    }

    @Override
    public int refusal(Entity entity) {
        return REPLICABLE;
    }

    @Override
    public void capture(Entity entity, TickState state) {
        state.modelState = new ReadSet(0L, List.of(), List.of(), List.of(), List.of(), List.of(),
            "none", false);
        state.modelAnswer = null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean compute(TickState state) {
        if (state.modelState instanceof CaptureRow row) {
            java.util.Map<String, String> predicted = new java.util.LinkedHashMap<>(row.fields());
            predicted.put("kind", "predicted");
            try {
                predicted.put("tick", Long.toString(Long.parseLong(row.fields().getOrDefault("tick", "0")) + 1L));
            } catch (NumberFormatException ignored) {
                predicted.put("tick", row.fields().getOrDefault("tick", "0"));
            }
            // Deterministic delta rule for the only field the capture supports: a zero plan
            // motion and no world read grow by one gravity step; all other fields stay identity
            // and are marked fallback in the capability table.
            String motion = row.fields().get("motion");
            if (motion != null) {
                String cleaned = motion.replace("(", "").replace(")", "").replace(" ", "");
                String[] parts = cleaned.split(",");
                try {
                    if (parts.length == 3 && Double.parseDouble(parts[0]) == 0.0D
                        && Double.parseDouble(parts[1]) == 0.0D && Double.parseDouble(parts[2]) == 0.0D) {
                        predicted.put("motion", "(0.0, -0.0784000015258789, 0.0)");
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            state.modelAnswer = predicted;
            computeRows.increment();
            return true;
        }
        if (!(state.modelState instanceof ReadSet readSet) || !readSet.valid()) {
            refusals.increment();
            return false;
        }
        if (readSet.behaviors().contains("unsupported")) {
            refusals.increment();
            return false;
        }
        long startedAt = System.nanoTime();
        int target = readSet.sensors().size();
        int behavior = readSet.behaviors().size();
        int nav = readSet.navigation().equals("idle") ? 0 : 1;
        state.modelAnswer = new long[]{target, behavior, nav};
        computeRows.increment();
        computeNanos.add(System.nanoTime() - startedAt);
        return true;
    }

    @Override
    public void apply(Entity entity, TickState state) {
        // The host apply seam is counted by noteApplied; this method only observes the answer.
    }

    @Override
    public void commitHostSteps(Entity entity, TickState state) {
        // RNG/events/collision/landing/entity/POI changes stay on the host side.
    }

    @Override
    public void noteCompute(long nanos) {
        computeNanos.add(nanos);
    }

    @Override
    public long computeNanos() {
        return computeNanos.sum();
    }

    @Override
    public long computeRows() {
        return computeRows.sum();
    }

    @Override
    public void noteApplied() {
        applied.increment();
    }

    @Override
    public long appliedCount() {
        return applied.sum();
    }

    @Override
    public void noteRefusal(int reason) {
        refusals.increment();
    }

    @Override
    public LongAdder[] refusalCounts() {
        return new LongAdder[]{refusals};
    }

    @Override
    public String[] refusalNames() {
        return new String[]{"read_set_or_behavior"};
    }

    @Override
    public void reset() {
        computeNanos.reset();
        computeRows.reset();
        applied.reset();
        refusals.reset();
    }
}
