/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The contract the event face adds to the common kernel. The test resolves the probe by name, so
 * a tree without it fails here and states what is missing. */
class P3EventOrderContractTest {

    private static final String PROBE =
        "io.izzel.arclight.common.prts.kernel.observe.EventOrderProbe";

    @Test
    void theEventProbeExistsWithItsEntryPoints() throws Exception {
        Class<?> probe = Class.forName(PROBE);
        assertNotNull(probe.getDeclaredMethod("install"));
        assertNotNull(probe.getDeclaredMethod("uninstall"));
        assertNotNull(probe.getDeclaredMethod("reset"));
        assertNotNull(probe.getDeclaredMethod("begin", Object.class, Object[].class,
            boolean.class));
        assertNotNull(probe.getDeclaredMethod("invokedCurrent", Object.class));
        assertNotNull(probe.getDeclaredMethod("end", Object.class, boolean.class));
        assertNotNull(probe.getDeclaredMethod("reading"));
    }

    @Test
    void theProbePublishesOnlyAfterAFrozenRootDispatch() throws Exception {
        Class<?> probe = Class.forName(PROBE);
        probe.getDeclaredMethod("install").invoke(null);
        try {
            System.setProperty("arclight.prts.eventOrderProbe", "true");
            probe.getDeclaredMethod("reset").invoke(null);
            Method begin = probe.getDeclaredMethod("begin", Object.class, Object[].class,
                boolean.class);
            Method end = probe.getDeclaredMethod("end", Object.class, boolean.class);
            Object token = begin.invoke(null, new Object(), new Object[] {new Object()}, true);
            end.invoke(null, token, false);
            Object reading = probe.getDeclaredMethod("reading").invoke(null);
            assertTrue(reading instanceof java.util.Optional<?> present && present.isPresent());
        } finally {
            System.clearProperty("arclight.prts.eventOrderProbe");
            probe.getDeclaredMethod("uninstall").invoke(null);
        }
    }
}
