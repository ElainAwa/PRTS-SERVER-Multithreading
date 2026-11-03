/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The contract the decoupled placement adds to the existing observer. The test resolves the entry
 * by name, so a tree without it fails here and states what is missing. */
class P3B01BPlacementContractTest {

    private static final String OBSERVER =
        "io.izzel.arclight.common.prts.kernel.observe.TickDigestObserver";

    @Test
    void theDecoupledPlacementEntryExists() throws Exception {
        Class<?> observer = Class.forName(OBSERVER);
        assertNotNull(observer.getDeclaredMethod("publishPlacement", long.class));
        assertNotNull(observer.getDeclaredMethod("clearMailboxPlacement"));
        assertNotNull(observer.getDeclaredMethod("forgetMailboxPlacement", String.class));
        Field interval = observer.getDeclaredField("PLACEMENT_REFRESH_TICKS");
        assertTrue(interval.getInt(null) > 0);
    }

    @Test
    void theEntryIsReachableWithoutArmingTheDigestWindow() throws Exception {
        Class<?> observer = Class.forName(OBSERVER);
        Method publish = observer.getDeclaredMethod("publishPlacement", long.class);
        assertNotNull(publish);
        Method armed = observer.getDeclaredMethod("armed");
        Object instance = observer.getDeclaredConstructor(int.class, java.util.List.class)
            .newInstance(0, java.util.List.of());
        assertTrue((Boolean) armed.invoke(instance) == false);
    }
}
