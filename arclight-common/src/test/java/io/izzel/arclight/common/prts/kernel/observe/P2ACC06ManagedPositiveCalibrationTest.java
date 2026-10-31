/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The test-only positive calibration is off by default; when enabled it issues one managed load and
 * makes the formal window publish one. The pure entry also covers disabled/no-world/off-thread. */
class P2ACC06ManagedPositiveCalibrationTest {

    private static final BlockPos POS = new BlockPos(16, 64, 16);
    private static final String WORLD = "minecraft:overworld";
    private static final long CHUNK = ChunkPos.asLong(1, 1);

    @AfterEach
    void cleanup() {
        System.clearProperty(ManagedPositiveCalibration.PROPERTY);
        MaterializedUnloadedReadings.reset();
    }

    @Test
    void propertyDefaultsOff() {
        assertFalse(ManagedPositiveCalibration.enabled());
        assertEquals(ManagedPositiveCalibration.Result.DISABLED,
            ManagedPositiveCalibration.calibrate(false, true, true, null, POS, (level, pos) -> true));
        assertFalse(MaterializedUnloadedReadings.windowOpen());
    }

    @Test
    void ownerLoadedWindowPublishesOne() {
        ManagedPositiveCalibration.Result result = ManagedPositiveCalibration.calibrate(true, true,
            true, null, POS, (level, pos) -> {
                MaterializedUnloadedReadings.onSynchronousRead("", CHUNK, true, true, true);
                return true;
            });
        assertEquals(ManagedPositiveCalibration.Result.POSITIVE, result);
        assertEquals(OptionalLong.of(1L), MaterializedUnloadedReadings.publishedValue());
        assertTrue(MaterializedUnloadedReadings.failOnPositive());
    }

    @Test
    void ownerNoLoadPublishesLegalZero() {
        ManagedPositiveCalibration.Result result = ManagedPositiveCalibration.calibrate(true, true,
            true, null, POS, (level, pos) -> false);
        assertEquals(ManagedPositiveCalibration.Result.NO_LOAD, result);
        assertEquals(OptionalLong.of(0L), MaterializedUnloadedReadings.publishedValue());
        assertFalse(MaterializedUnloadedReadings.failOnPositive());
    }

    @Test
    void offThreadAndNoWorldDoNotStartAWindow() {
        assertEquals(ManagedPositiveCalibration.Result.NOT_OWNER,
            ManagedPositiveCalibration.calibrate(true, false, true, null, POS, (level, pos) -> true));
        assertEquals(ManagedPositiveCalibration.Result.NO_WORLD,
            ManagedPositiveCalibration.calibrate(true, true, false, null, null, (level, pos) -> true));
        assertFalse(MaterializedUnloadedReadings.windowOpen());
    }

    @Test
    void loaderFailureIsSourceUnavailableAndNotPublished() {
        ManagedPositiveCalibration.Result result = ManagedPositiveCalibration.calibrate(true, true,
            true, null, POS, (level, pos) -> {
                throw new IllegalStateException("load failed");
            });
        assertEquals(ManagedPositiveCalibration.Result.NO_LOAD, result);
        assertFalse(MaterializedUnloadedReadings.publishedValue().isPresent());
    }
}
