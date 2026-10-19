package io.izzel.arclight.common.mod.server.world;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BukkitValueSkipTest {

    private final List<String> log = new ArrayList<>();

    @AfterEach
    void reset() {
        BukkitValueSkip.resetForTests();
    }

    @Test
    void nullWorldIsSkipped() {
        assertTrue(BukkitValueSkip.shouldSkip(null));
    }

    @Test
    void objectWorldIsNotSkipped() {
        assertFalse(BukkitValueSkip.shouldSkip(new Object()));
    }

    @Test
    void loadNullDoesNotRunAction() {
        boolean[] ran = {false};
        assertTrue(BukkitValueSkip.shouldSkip(null));
        assertFalse(BukkitValueSkip.runIfWorld(null, () -> ran[0] = true));
        assertFalse(ran[0]);
    }

    @Test
    void loadWithWorldRunsAction() {
        boolean[] ran = {false};
        assertTrue(BukkitValueSkip.runIfWorld(new Object(), () -> ran[0] = true));
        assertTrue(ran[0]);
    }

    @Test
    void eachPathLogsOnce() {
        BukkitValueSkip.setLogSinkForTests(log::add);
        assertTrue(BukkitValueSkip.markAndLog("save"));
        assertFalse(BukkitValueSkip.markAndLog("save"));
        assertTrue(BukkitValueSkip.markAndLog("load"));
        assertEquals(List.of("save", "load"), log);
    }
}
