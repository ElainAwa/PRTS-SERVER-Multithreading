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
    void addWorldAppearsOnceInsideGuard() throws Exception {
        java.nio.file.Path source = java.nio.file.Path.of(
            "src/main/java/io/izzel/arclight/common/mixin/core/server/level",
            "ServerLevelMixin.java");
        if (!java.nio.file.Files.exists(source)) {
            source = java.nio.file.Path.of(
                "arclight-common/src/main/java/io/izzel/arclight/common/mixin/core/server/level",
                "ServerLevelMixin.java");
        }
        String text = java.nio.file.Files.readString(source);
        long calls = text.lines().filter(line -> line.contains("addWorld(")).count();
        assertEquals(1L, calls);
        int guard = text.indexOf("runIfWorld(this.world");
        int add = text.indexOf("addWorld(");
        int close = text.indexOf("})", guard);
        assertTrue(guard >= 0 && add > guard && close > add);
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
