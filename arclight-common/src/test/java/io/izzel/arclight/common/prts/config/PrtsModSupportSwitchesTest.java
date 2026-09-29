/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.config;

import io.izzel.arclight.common.prts.config.PrtsConfigManager.Entry;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The switch surface of the mod interoperability layer.
 *
 * <p>A patch reads its switch by name, and a name the category does not declare is a mistake in the
 * code that reads it, not an operator input: the read throws, and in a mixin handler that means the
 * server fails while a mod is present. The sets below are the names the patches read, so a switch
 * that is used and not declared, a switch that is declared and never used, and a default that
 * drifted away from the policy of the layer all fail here instead of on a server.</p>
 */
class PrtsModSupportSwitchesTest {

    /** Switches that repair a fault; they are on unless an operator turns them off. */
    private static final Set<String> FAULT_SWITCHES = Set.of(
        "guard-create-tree-cutter-bounds",
        "guard-sable-voxel-cache",
        "serialize-sable-native-calls",
        "narrow-unlockable-recipes-login-sync",
        "guard-minecolonies-compat-discovery",
        "resync-tacz-gun-state-on-respawn");

    /** Switches that only change how much work a mod does; they are off until asked for. */
    private static final Set<String> WORK_SWITCHES = Set.of(
        "shorten-sbw-projectile-life",
        "spread-sbw-motion-sync",
        "narrow-sbw-particle-viewers",
        "throttle-sbw-iff-payloads",
        "narrow-sbw-vehicle-shoot");

    @Test
    void everySwitchAPatchReadsIsDeclared() {
        Map<String, Boolean> declared = declaredFeatures();

        FAULT_SWITCHES.forEach(name -> assertTrue(declared.containsKey(name), name + " is declared"));
        WORK_SWITCHES.forEach(name -> assertTrue(declared.containsKey(name), name + " is declared"));
        FAULT_SWITCHES.forEach(name -> assertTrue(PrtsConfigManager.feature(PrtsConfigManager.MODSUPPORT, name),
            name + " resolves without a loaded file"));
        WORK_SWITCHES.forEach(name -> assertFalse(PrtsConfigManager.feature(PrtsConfigManager.MODSUPPORT, name),
            name + " resolves without a loaded file"));
    }

    @Test
    void faultSwitchesDefaultOnAndWorkSwitchesDefaultOff() {
        Map<String, Boolean> declared = declaredFeatures();

        FAULT_SWITCHES.forEach(name -> assertTrue(declared.get(name), name + " defaults to on"));
        WORK_SWITCHES.forEach(name -> assertFalse(declared.get(name), name + " defaults to off"));
    }

    @Test
    void theTreeSearchBoundsAreDeclaredAndOffByDefault() {
        Map<String, PrtsConfigManager.IntSetting> numbers = declaredNumbers();

        assertTrue(numbers.containsKey("tree-cutter-node-budget"), "the node budget is declared");
        assertTrue(numbers.containsKey("tree-cutter-time-budget-ms"), "the time budget is declared");
        assertEquals(0, PrtsConfigManager.number(PrtsConfigManager.MODSUPPORT, "tree-cutter-node-budget"));
        assertEquals(0, PrtsConfigManager.number(PrtsConfigManager.MODSUPPORT, "tree-cutter-time-budget-ms"));
    }

    private static Map<String, Boolean> declaredFeatures() {
        Entry entry = PrtsConfigManager.entries().get(PrtsConfigManager.MODSUPPORT);
        return entry.features();
    }

    private static Map<String, PrtsConfigManager.IntSetting> declaredNumbers() {
        Entry entry = PrtsConfigManager.entries().get(PrtsConfigManager.MODSUPPORT);
        return entry.numbers();
    }
}
