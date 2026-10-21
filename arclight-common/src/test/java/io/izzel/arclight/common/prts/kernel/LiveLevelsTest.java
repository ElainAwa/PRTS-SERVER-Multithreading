/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveLevelsTest {

    @Test
    void aNullServerClearsTheViewInsteadOfLeavingTheOldLevel() {
        Object held = new Object();
        LiveLevels.installForTest(Map.of("minecraft:overworld", held));
        assertSame(held, LiveLevels.level("minecraft:overworld"));

        LiveLevels.refresh(null);

        assertNull(LiveLevels.level("minecraft:overworld"), "a stopped server leaves no level");
        assertFalse(LiveLevels.ambiguous("minecraft:overworld"));
    }

    @Test
    void aDuplicateWorldIdIsNeverResolvedOrAliased() {
        Object first = new Object();
        Object second = new Object();
        LiveLevels.installForTest(Map.of("dup:world", first), Set.of("dup:world"));

        assertNull(LiveLevels.level("dup:world"));
        assertTrue(LiveLevels.ambiguous("dup:world"));
        assertTrue(LiveLevels.worldOf(first).isEmpty());
        assertTrue(LiveLevels.worldOf(second).isEmpty());
        assertTrue(LiveLevels.consistentForTest("dup:world"));
    }

    @Test
    void concurrentReadersNeverSeeAnAmbiguousWorldWithAnOldLevel() throws Exception {
        Object old = new Object();
        LiveLevels.installForTest(Map.of("race:world", old));
        AtomicBoolean bad = new AtomicBoolean();
        CountDownLatch done = new CountDownLatch(2);
        Thread reader = new Thread(() -> {
            try {
                for (int index = 0; index < 20_000; index++) {
                    if (!LiveLevels.consistentForTest("race:world")) {
                        bad.set(true);
                    }
                }
            } finally {
                done.countDown();
            }
        });
        Thread writer = new Thread(() -> {
            try {
                for (int index = 0; index < 20_000; index++) {
                    if ((index & 1) == 0) {
                        LiveLevels.installForTest(Map.of("race:world", old), Set.of("race:world"));
                    } else {
                        LiveLevels.installForTest(Map.of("race:world", old));
                    }
                }
            } finally {
                LiveLevels.installForTest(Map.of("race:world", old));
                done.countDown();
            }
        });
        reader.start();
        writer.start();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        assertFalse(bad.get(), "the snapshot never exposes an ambiguous world with a level");
        LiveLevels.clear();
    }
}
