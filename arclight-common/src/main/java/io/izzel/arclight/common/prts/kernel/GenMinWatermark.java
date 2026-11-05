/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import io.izzel.arclight.common.prts.kernel.observe.ChunkPipelineProgress;
import io.izzel.arclight.common.prts.kernel.waitpoints.WaitProgress;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The conservative multi-world watermark for the existing generation wait-point row. It publishes
 * the minimum cumulative generation-step count of the tracked worlds and only moves when that
 * minimum moves; a stalled world therefore holds the row while others advance. The row is bound
 * only while the A02d producer is installed and at least one world is tracked; reset, unload, an
 * epoch change and a disabled producer unbind it instead of publishing a synthetic zero. */
final class GenMinWatermark {

    static final String SOURCE = "chunk.pipeline.gen_min_watermark";
    private static final int MAX_WORLDS = 256;

    private boolean bound;
    private long watermark;
    private Map<String, Long> worlds;

    /** Synchronizes the binding with one bounded A02d snapshot. */
    synchronized void sync(WaitProgress progress, boolean installed,
                           List<ChunkPipelineProgress.WorldReading> readings) {
        if (progress == null) {
            return;
        }
        if (!installed || readings == null || readings.isEmpty()) {
            clear(progress);
            return;
        }
        Map<String, Long> current = new LinkedHashMap<>();
        long minimum = Long.MAX_VALUE;
        for (ChunkPipelineProgress.WorldReading reading : readings) {
            if (reading == null || reading.worldId() == null || reading.worldId().isBlank()) {
                continue;
            }
            if (current.size() >= MAX_WORLDS || current.containsKey(reading.worldId())) {
                continue;
            }
            current.put(reading.worldId(), reading.epoch());
            minimum = Math.min(minimum, Math.max(0L, reading.genSteps()));
        }
        if (current.isEmpty() || minimum == Long.MAX_VALUE) {
            clear(progress);
            return;
        }
        if (worlds == null) {
            worlds = current;
            watermark = minimum;
            bind(progress);
            return;
        }
        if (!worlds.equals(current)) {
            unbind(progress);
            worlds = current;
            watermark = 0L;
            return;
        }
        if (minimum > watermark) {
            watermark = minimum;
        }
        if (!bound) {
            bind(progress);
        }
    }

    /** Removes the binding and forgets the world generation; a later bind starts a fresh watermark. */
    synchronized void clear(WaitProgress progress) {
        if (progress != null && bound) {
            progress.unbind("gen");
        }
        bound = false;
        watermark = 0L;
        worlds = null;
    }

    private void bind(WaitProgress progress) {
        progress.bind("gen", SOURCE, () -> watermark);
        bound = true;
    }

    private void unbind(WaitProgress progress) {
        if (bound) {
            progress.unbind("gen");
        }
        bound = false;
    }
}
