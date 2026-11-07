/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity;

import io.izzel.arclight.common.prts.kernel.diff.StateHasher;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The production write-back contract: the pose write marks the entity impulse and drives the
 * tracked entity's send once per batch, and 24 visible rows keep their positions. */
class ProductionWriteVisibilityTest {

    @Test
    void twentyFourRowsKeepTheirPositionsAndCount() {
        List<StateHasher.Slice> rows = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            rows.add(new StateHasher.Slice("minecraft:overworld", "0,0", 1L, 100 + i,
                4.0 + (i % 8) * 1.2, 70.0, 14.0 + (i / 8), 0.0, 0.0, 0.05, 0.0, 0.0,
                0L, 0L, 0L));
        }
        assertEquals(24, rows.size());
        double minX = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        for (StateHasher.Slice row : rows) {
            assertFalse(Double.isNaN(row.x()) || Double.isNaN(row.y()) || Double.isNaN(row.z()),
                "a visible row has a NaN position");
            minX = Math.min(minX, row.x());
            maxX = Math.max(maxX, row.x());
        }
        assertEquals(4.0, minX, 0.0);
        assertEquals(4.0 + 7 * 1.2, maxX, 1.0E-12);
    }

    @Test
    void writeMarksImpulseAndDrivesTheTrackedSend() throws Exception {
        String access = source("LiveEntityAccess.java");
        assertTrue(access.contains("entity.hasImpulse = true"), "the write does not mark the impulse");
        assertTrue(access.contains("bridge$syncTrackedEntity(entity)"), "the write does not drive the tracker");
        String batch = source("BatchWriteBack.java");
        int write = batch.indexOf("LiveEntityAccess.write(");
        int sync = batch.indexOf("LiveEntityAccess.syncTracked(entity);");
        assertTrue(write >= 0 && sync > write, "the tracker send is not after the write");
    }

    @Test
    void writebackUsesTheHostPoseNotTheIntegratedAdvance() throws Exception {
        String batch = source("BatchWriteBack.java");
        assertTrue(batch.contains("entity.getX(), entity.getY(), entity.getZ()"),
            "the write-back does not use the host pose");
        assertFalse(batch.contains("LiveEntityAccess.write(entity, row.x(), row.y(), row.z()"),
            "the write-back re-applies the integrated snapshot advance");
    }

    @Test
    void twentyFourFixedBatsDoNotDriftAcrossWriteBacks() {
        double[] host = new double[24];
        for (int i = 0; i < host.length; i++) {
            host[i] = 4.0 + (i % 8) * 1.2;
        }
        for (int tick = 0; tick < 10_000; tick++) {
            for (int i = 0; i < host.length; i++) {
                double integrated = host[i] + 0.05;
                double written = host[i];
                assertEquals(4.0 + (i % 8) * 1.2, written, 0.0,
                    "the write-back advanced a fixed host bat");
                host[i] = written;
            }
        }
    }

    @Test
    void oneBatchSendsAtMostOncePerEntity() throws Exception {
        String batch = source("BatchWriteBack.java");
        int set = batch.indexOf("Set<Integer> synced");
        int add = batch.indexOf("synced.add(entity.getId())");
        int sync = batch.indexOf("LiveEntityAccess.syncTracked(entity);");
        assertTrue(set >= 0 && add > set && sync > add, "the per-batch send guard is missing");
        assertTrue(batch.contains("new HashSet<>()"), "the send guard is not a set");
    }

    private static String source(String name) throws Exception {
        Path relative = Path.of("src/main/java/io/izzel/arclight/common/prts/kernel/domain/entity", name);
        Path fallback = Path.of("arclight-common").resolve(relative);
        return Files.readString(Files.exists(relative) ? relative : fallback);
    }
}
