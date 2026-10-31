/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The formal materialized-unloaded field is published only by a complete, source-valid, lossless
 * window; rejected/loaded/NO_WORLD reads and duplicate events do not count. */
class P2ACC06MaterializedUnloadedReadingsTest {

    @AfterEach
    void cleanup() {
        MaterializedUnloadedReadings.reset();
    }

    @Test
    void countOnlyCompletedColdReadsAndDedup() {
        MaterializedUnloadedReadings.beginWindow(2);
        long first = MaterializedUnloadedReadings.begin("world", 11L, "peek");
        MaterializedUnloadedReadings.onSynchronousRead("world", 11L, true, true, true);
        MaterializedUnloadedReadings.onSynchronousRead("world", 11L, true, true, true);
        MaterializedUnloadedReadings.end(first, MaterializedUnloadedReadings.Outcome.COMPLETE);
        long second = MaterializedUnloadedReadings.begin("world", 12L, "peek");
        MaterializedUnloadedReadings.onSynchronousRead("world", 12L, true, true, true);
        MaterializedUnloadedReadings.end(second, MaterializedUnloadedReadings.Outcome.REJECTED);
        assertEquals(OptionalLong.of(1L), MaterializedUnloadedReadings.publishedValue());
        assertTrue(MaterializedUnloadedReadings.failOnPositive());
    }

    @Test
    void completeKnownEmptyPublishesLegalZero() {
        MaterializedUnloadedReadings.beginWindow(1);
        long read = MaterializedUnloadedReadings.begin("world", 1L, "peek");
        MaterializedUnloadedReadings.end(read, MaterializedUnloadedReadings.Outcome.COMPLETE);
        assertEquals(OptionalLong.of(0L), MaterializedUnloadedReadings.publishedValue());
        assertFalse(MaterializedUnloadedReadings.failOnPositive());
    }

    @Test
    void incompleteOrUnavailableWindowsAreNotPublished() {
        MaterializedUnloadedReadings.beginWindow(2);
        long read = MaterializedUnloadedReadings.begin("world", 1L, "peek");
        MaterializedUnloadedReadings.end(read, MaterializedUnloadedReadings.Outcome.COMPLETE);
        assertEquals(OptionalLong.empty(), MaterializedUnloadedReadings.publishedValue());
        MaterializedUnloadedReadings.sourceUnavailable();
        assertEquals(OptionalLong.empty(), MaterializedUnloadedReadings.publishedValue());
    }

    @Test
    void lossTruncationResetAndNoWorldAreNotPublishedAsZero() {
        MaterializedUnloadedReadings.beginWindow(1);
        long read = MaterializedUnloadedReadings.begin("world", 1L, "peek");
        MaterializedUnloadedReadings.end(read, MaterializedUnloadedReadings.Outcome.NO_WORLD);
        MaterializedUnloadedReadings.truncation();
        assertEquals(OptionalLong.empty(), MaterializedUnloadedReadings.publishedValue());
    }

    @Test
    void loadedSnapshotAndNoContextDoNotCount() {
        MaterializedUnloadedReadings.beginWindow(2);
        long loaded = MaterializedUnloadedReadings.begin("world", 1L, "peek");
        MaterializedUnloadedReadings.onSynchronousRead("world", 1L, true, true, true);
        MaterializedUnloadedReadings.end(loaded, MaterializedUnloadedReadings.Outcome.LOADED_SNAPSHOT);
        long cold = MaterializedUnloadedReadings.begin("world", 2L, "peek");
        MaterializedUnloadedReadings.onSynchronousRead("world", 99L, true, true, true);
        MaterializedUnloadedReadings.end(cold, MaterializedUnloadedReadings.Outcome.COMPLETE);
        assertEquals(OptionalLong.of(0L), MaterializedUnloadedReadings.publishedValue());
    }

    @Test
    void noWorldWindowIsNotASourceFailure() {
        MaterializedUnloadedReadings.beginWindow(1);
        long read = MaterializedUnloadedReadings.begin("", 0L, "peek");
        MaterializedUnloadedReadings.end(read, MaterializedUnloadedReadings.Outcome.NO_WORLD);
        assertEquals(OptionalLong.of(0L), MaterializedUnloadedReadings.publishedValue());
    }

    @Test
    void ringOverflowMarksTruncatedAndWithholdsTheField() {
        MaterializedUnloadedReadings.beginWindow(1);
        long first = MaterializedUnloadedReadings.begin("world", 0L, "peek");
        for (int index = 1; index < 257; index++) {
            MaterializedUnloadedReadings.begin("world", index, "peek");
        }
        MaterializedUnloadedReadings.onSynchronousRead("world", 0L, true, true, true);
        MaterializedUnloadedReadings.end(first, MaterializedUnloadedReadings.Outcome.COMPLETE);
        assertFalse(MaterializedUnloadedReadings.publishedValue().isPresent());
    }

    @Test
    void crossWorldAndDifferentChunkDoNotCount() {
        MaterializedUnloadedReadings.beginWindow(2);
        long first = MaterializedUnloadedReadings.begin("world", 1L, "peek");
        MaterializedUnloadedReadings.onSynchronousRead("other", 1L, true, true, true);
        MaterializedUnloadedReadings.onSynchronousRead("world", 2L, true, true, true);
        MaterializedUnloadedReadings.end(first, MaterializedUnloadedReadings.Outcome.COMPLETE);
        long second = MaterializedUnloadedReadings.begin("other", 1L, "peek");
        MaterializedUnloadedReadings.end(second, MaterializedUnloadedReadings.Outcome.COMPLETE);
        assertEquals(OptionalLong.of(0L), MaterializedUnloadedReadings.publishedValue());
    }

    @Test
    void resetAndEpochDriftAreNotCounted() {
        MaterializedUnloadedReadings.beginWindow(1);
        MaterializedUnloadedReadings.begin("world", 1L, "peek");
        MaterializedUnloadedReadings.markReset();
        assertFalse(MaterializedUnloadedReadings.publishedValue().isPresent());
        assertTrue(MaterializedUnloadedReadings.resetDetected());

        MaterializedUnloadedReadings.reset();
        MaterializedUnloadedReadings.beginWindow(1);
        long clean = MaterializedUnloadedReadings.begin("world", 1L, "peek");
        MaterializedUnloadedReadings.end(clean, MaterializedUnloadedReadings.Outcome.COMPLETE);
        assertEquals(OptionalLong.of(0L), MaterializedUnloadedReadings.publishedValue());

        MaterializedUnloadedReadings.beginWindow(1);
        long old = MaterializedUnloadedReadings.begin("world", 1L, "peek");
        MaterializedUnloadedReadings.beginWindow(1);
        MaterializedUnloadedReadings.onSynchronousRead("world", 1L, true, true, true);
        MaterializedUnloadedReadings.end(old, MaterializedUnloadedReadings.Outcome.COMPLETE);
        assertFalse(MaterializedUnloadedReadings.publishedValue().isPresent());
        assertTrue(MaterializedUnloadedReadings.windowOpen());
    }
}
