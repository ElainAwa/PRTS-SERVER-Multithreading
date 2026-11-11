/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

/** Internal AI scale knobs; defaults keep the 8-actor slice and are not formal readout keys. */
final class AiScaleConfig {

    static final String ACTORS = "arclight.prts.aiActors";
    static final String WORKERS = "arclight.prts.aiWorkers";
    static final String BATCH = "arclight.prts.aiBatch";
    static final String SHARD = "arclight.prts.aiCaptureShard";
    static final String COMMIT_BATCH = "arclight.prts.aiCommitBatch";
    static final int MIN_ACTORS = 8;
    static final int MAX_ACTORS = 2048;
    static final int DEFAULT_ACTORS = 8;
    static final int DEFAULT_BATCH = 32;
    static final int DEFAULT_SHARD = 256;
    static final int DEFAULT_COMMIT_BATCH = 256;

    private AiScaleConfig() {
    }

    static int actors() {
        return clamp(Integer.getInteger(ACTORS, DEFAULT_ACTORS), MIN_ACTORS, MAX_ACTORS);
    }

    static int workers() {
        int cpus = Math.max(1, Runtime.getRuntime().availableProcessors());
        return clamp(Integer.getInteger(WORKERS, cpus), 1, cpus);
    }

    static int batch() {
        return clamp(Integer.getInteger(BATCH, DEFAULT_BATCH), 1, actors());
    }

    static int shard() {
        return clamp(Integer.getInteger(SHARD, DEFAULT_SHARD), 64, 512);
    }

    static int commitBatch() {
        return clamp(Integer.getInteger(COMMIT_BATCH, DEFAULT_COMMIT_BATCH), 1, MAX_ACTORS);
    }

    static int captureShards(int radius) {
        int shard = shard();
        int perAxis = (radius * 2 + shard - 1) / shard;
        return perAxis * perAxis;
    }

    static int batches(int actors) {
        if (actors <= 0) return 0;
        int size = batch();
        return (actors + size - 1) / size;
    }

    static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
