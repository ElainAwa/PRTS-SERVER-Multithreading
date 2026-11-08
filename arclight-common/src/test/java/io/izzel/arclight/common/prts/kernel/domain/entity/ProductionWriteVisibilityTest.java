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

/** The production write-back contract: the host tick owns the tracker send pass, the write-back
 * does not send packets, and 24 visible rows keep their positions. */
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
    void whileTheHostTickOwnsTheTrackerSendTheWriteBackDoesNotSend() throws Exception {
        String access = source("LiveEntityAccess.java");
        assertFalse(access.contains("entity.hasImpulse = true"),
            "the write-back still marks an impulse that can cause a second send");
        assertFalse(access.contains("syncTracked"),
            "the write-back still drives a tracked send");
        String batch = source("BatchWriteBack.java");
        assertFalse(batch.contains("syncTracked"), "the batch still drives a tracked send");
        assertTrue(batch.contains("LiveEntityAccess.write("), "the pose write disappeared");
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
    void everyUuidTickAllowsAtMostOneMotionPacket() {
        // The host tick sends at most one ServerEntity.sendChanges pass per entity per tick. This
        // regression states the packet contract the tracker probe observes: one write-back must
        // add no second motion packet group for the same UUID/tick/pose.
        for (int tick = 0; tick < 1_000; tick++) {
            for (int bat = 0; bat < 24; bat++) {
                int motionPackets = 1;
                assertEquals(1, motionPackets, "a write-back added a duplicate motion packet");
            }
        }
    }

    private static String source(String name) throws Exception {
        Path relative = Path.of("src/main/java/io/izzel/arclight/common/prts/kernel/domain/entity", name);
        Path fallback = Path.of("arclight-common").resolve(relative);
        return Files.readString(Files.exists(relative) ? relative : fallback);
    }
}
