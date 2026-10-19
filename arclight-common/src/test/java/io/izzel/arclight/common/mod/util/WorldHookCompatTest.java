package io.izzel.arclight.common.mod.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldHookCompatTest {

    private final List<String> log = new ArrayList<>();

    @AfterEach
    void reset() {
        WorldHookCompat.resetForTests();
    }

    @Test
    void normalCraftWorldRunsActionOnce() {
        WorldHookCompat.setLogSinkForTests(log::add);
        List<String> seen = new ArrayList<>();
        WorldHookCompat.enterBukkitHook("normal", true, () -> seen.add("A"));
        assertEquals(List.of("A"), seen);
        assertTrue(log.isEmpty());
    }

    @Test
    void missingCraftWorldSkipsActionAndLogsOnce() {
        WorldHookCompat.setLogSinkForTests(log::add);
        int[] runs = {0};
        WorldHookCompat.enterBukkitHook("skip.once", false, () -> runs[0]++);
        WorldHookCompat.enterBukkitHook("skip.once", false, () -> runs[0]++);
        assertEquals(0, runs[0]);
        assertEquals(List.of("skip.once"), log);
    }

    @Test
    void mixedSitesRunCraftWorldsInOrderAndLogOnlySkips() {
        WorldHookCompat.setLogSinkForTests(log::add);
        List<String> seen = new ArrayList<>();
        WorldHookCompat.enterBukkitHook("site.A", true, () -> seen.add("A"));
        WorldHookCompat.enterBukkitHook("site.B", false, () -> seen.add("B"));
        WorldHookCompat.enterBukkitHook("site.C", true, () -> seen.add("C"));
        assertEquals(List.of("A", "C"), seen);
        assertEquals(List.of("site.B"), log);
    }

    @Test
    void hookExceptionPropagatesAndStateIsClean() {
        WorldHookCompat.setLogSinkForTests(log::add);
        RuntimeException boom = new RuntimeException("boom");
        RuntimeException got = assertThrows(RuntimeException.class,
            () -> WorldHookCompat.enterBukkitHook("fails", true, () -> {
                throw boom;
            }));
        assertSame(boom, got);
        assertTrue(log.isEmpty());
        WorldHookCompat.enterBukkitHook("fails", false, () -> { });
        assertEquals(List.of("fails"), log);
        WorldHookCompat.resetForTests();
    }

    @Test
    void everyStartupSiteNameIsDistinctAndLoggedOnce() {
        WorldHookCompat.setLogSinkForTests(log::add);
        String[] sites = {
            "worldInit.populators", "worldInit.event",
            "prepareLevels.keepSpawn", "prepareLevels.loadEvent",
            "initWorld.populators", "initWorld.event",
            "prepareLevelsSingle.guard"
        };
        for (String site : sites) {
            WorldHookCompat.enterBukkitHook(site, false, () -> { });
            WorldHookCompat.enterBukkitHook(site, false, () -> { });
        }
        assertEquals(List.of(sites), log);
    }
}
