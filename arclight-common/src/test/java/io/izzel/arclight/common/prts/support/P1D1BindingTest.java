/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.support;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P1-D1 old-red/new-green: the host entity-tick subscription follows the kernel, not the fixture
 * property; the ownership action itself is still explicitly armed. */
class P1D1BindingTest {

    @Test
    void theBindingPolicyIsKernelDriven() {
        assertTrue(EntityTickBindingPolicy.shouldBind(true));
        assertFalse(EntityTickBindingPolicy.shouldBind(false));
    }

    @Test
    void theHostBindingFollowsKernelOnOff() throws Exception {
        String events = Files.readString(Path.of(
            "../arclight-neoforge/src/main/java/io/izzel/arclight/neoforge/prts/kernel/PrtsEntityOwnershipEvents.java"));
        assertTrue(events.contains("KernelSettings.enabled()"));
        assertTrue(events.contains("EntityTickBindingPolicy.shouldBind"));
        assertTrue(events.contains("NeoForge.EVENT_BUS.register"));
        assertTrue(events.contains("NeoForge.EVENT_BUS.unregister"));
        String kernel = Files.readString(Path.of(
            "../arclight-neoforge/src/main/java/io/izzel/arclight/neoforge/prts/kernel/PrtsKernelEvents.java"));
        assertTrue(kernel.contains("PrtsEntityOwnershipEvents.syncSubscription(true)"));
        assertTrue(kernel.contains("PrtsEntityOwnershipEvents.syncSubscription(false)"));
    }
}
