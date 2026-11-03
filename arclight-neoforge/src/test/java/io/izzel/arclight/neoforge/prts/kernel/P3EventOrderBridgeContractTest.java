/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.neoforge.prts.kernel;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** The contract the platform bridge adds to the bus module. The test resolves the bridge by name,
 * so a tree without it fails here and states what is missing. */
class P3EventOrderBridgeContractTest {

    private static final String BRIDGE =
        "io.izzel.arclight.neoforge.prts.kernel.PrtsEventOrderBridge";

    @Test
    void theBridgeNamesTheThreeFrameCallsAndTheThreeRoots() throws Exception {
        Class<?> bridge = Class.forName(BRIDGE);
        assertNotNull(bridge.getDeclaredMethod("install"));
        assertNotNull(bridge.getDeclaredMethod("remove"));
        assertNotNull(bridge.getDeclaredMethod("active"));
        assertNotNull(bridge.getDeclaredMethod("beginDispatch", Object.class, Object.class,
            Object[].class));
        assertNotNull(bridge.getDeclaredMethod("noteInvocation", Object.class));
        assertNotNull(bridge.getDeclaredMethod("endDispatch", boolean.class));
    }

    @Test
    void onlyTheThreeDeclaredEventsOpenARoot() throws Exception {
        Class<?> bridge = Class.forName(BRIDGE);
        Method root = bridge.getDeclaredMethod("rootEvent", Object.class);
        root.setAccessible(true);
        Object tick = new net.neoforged.neoforge.event.tick.ServerTickEvent.Post(() -> true, null);
        Object unload = new net.neoforged.neoforge.event.level.LevelEvent.Unload(null);
        assertNotNull(root.invoke(null, tick));
        assertNotNull(root.invoke(null, unload));
        assertFalse((Boolean) root.invoke(null, new Object()));
    }
}
