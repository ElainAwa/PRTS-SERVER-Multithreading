/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the save identity answers before a server has been seen. */
class SaveIdentityObserverTest {

    @Test
    void withNoSourceEveryReadingAnswersItsZeroValue() {
        SaveIdentityObserver observer = new SaveIdentityObserver();
        assertFalse(observer.installed());
        assertFalse(observer.manifestTaken());
        assertTrue(observer.worlds().isEmpty());
        assertEquals("-", observer.levelDatMd5());
        assertEquals("-", observer.manifestHash());
        assertEquals(0L, observer.manifestFiles());
        assertEquals(0L, observer.manifestBytes());
    }

    @Test
    void aResetForgetsTheCachedManifest() {
        SaveIdentityObserver observer = new SaveIdentityObserver();
        observer.manifestHash();
        assertTrue(observer.manifestTaken());
        observer.reset();
        assertFalse(observer.manifestTaken());
        assertEquals("-", observer.manifestHash());
    }
}
