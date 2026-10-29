/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.support;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Executes unchanged mixin method bodies with boundary substitutes, not a transformed Minecraft server.
class LevelLifecyclePublicationTest {

    @TempDir
    static Path classes;

    @BeforeAll
    static void compileHostMethods() throws Exception {
        Path source = Path.of(
            "src/main/java/io/izzel/arclight/common/mixin/core/server/MinecraftServerMixin.java");
        if (!Files.exists(source)) {
            source = Path.of("arclight-common").resolve(source);
        }
        String text = Files.readString(source);
        String fixture = HOST.replace("STARTUP_METHOD", method(text, "private Object arclight$worldInit("))
            .replace("ADD_METHOD", method(text, "public void addLevel("));
        Path java = classes.resolve("LifecycleFixture.java");
        Files.writeString(java, fixture);
        Path wiring = classes.resolve("WorldLifecycleWiring.java");
        Files.writeString(wiring, """
            package io.izzel.arclight.common.prts.kernel;
            public final class WorldLifecycleWiring {
                private WorldLifecycleWiring() {}
                public static void onLevelLoaded(String worldId) {}
                public static void onLevelUnloaded(String worldId) {}
            }
            """);
        for (String suffix : List.of("", "$Snapshot", "$Mark", "$Refusal")) {
            String name = "io/izzel/arclight/common/prts/support/PrtsLevelLifecycle" + suffix + ".class";
            Path target = classes.resolve(name);
            Files.createDirectories(target.getParent());
            try (var input = PrtsLevelLifecycle.class.getClassLoader().getResourceAsStream(name)) {
                assertNotNull(input, name);
                Files.copy(input, target);
            }
        }
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "the fixture requires a JDK");
        assertEquals(0, compiler.run(null, null, null, "-proc:none", "-classpath", classes.toString(),
            "-d", classes.toString(), java.toString(), wiring.toString()));
    }

    @Test
    void theServerSingletonStartsWithAnEmptySnapshot() throws Throwable {
        run("firstAccess");
    }

    @Test
    void startupInitializerFailureDoesNotPublishAnUnregisteredDimension() throws Throwable {
        run("startupInitializerFailure");
    }

    @Test
    void startupInsertionFailureDoesNotPublishAnUnregisteredDimension() throws Throwable {
        run("startupInsertionFailure");
    }

    @Test
    void aStartupReplacementPublishesOnlyAfterTheMapHasChanged() throws Throwable {
        run("startupReplacement");
    }

    @Test
    void dynamicInsertionFailureDoesNotPublishAnUnregisteredDimension() throws Throwable {
        run("dynamicInsertionFailure");
    }

    @Test
    void dynamicLoadCallbacksSeeTheRegisteredDimensionAndItsSnapshot() throws Throwable {
        run("dynamicSuccess");
    }

    @Test
    void aNonLevelStartupInsertionDoesNotPublishALoad() throws Throwable {
        run("nonLevel");
    }

    private static void run(String scenario) throws Throwable {
        // Each loader gets its own real recorder so parallel tests cannot share the server singleton.
        try (var loader = new URLClassLoader(new java.net.URL[] {classes.toUri().toURL()},
            ClassLoader.getPlatformClassLoader())) {
            try {
                loader.loadClass("LifecycleFixture").getMethod("verify", String.class).invoke(null, scenario);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        }
    }

    private static String method(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, signature);
        int open = source.indexOf('{', start);
        int depth = 1;
        int end = open + 1;
        while (end < source.length() && depth != 0) {
            char next = source.charAt(end++);
            if (next == '{') depth++;
            if (next == '}') depth--;
        }
        assertEquals(0, depth, signature);
        return source.substring(start, end);
    }

    private static final String HOST = """
        import io.izzel.arclight.common.prts.support.PrtsLevelLifecycle;
        import io.izzel.arclight.common.prts.kernel.WorldLifecycleWiring;
        import java.util.*;

        class MinecraftServer {}
        public class LifecycleFixture extends MinecraftServer {
            final Map<Object, Object> levels;
            int callbacks;
            int dirty;
            static final RuntimeException FAILURE = new IllegalStateException("boundary failure");
            LifecycleFixture() { this(new LinkedHashMap<>()); }
            LifecycleFixture(Map<Object, Object> levels) { this.levels = levels; }

            STARTUP_METHOD
            ADD_METHOD

            void arclight$onServerLoad(ServerLevel level) {
                check(levels.get(level.dimension()) == level, "callback sees the inserted level");
                check(PrtsLevelLifecycle.server().current().loadedIds.contains(level.id),
                    "callback sees the published load");
                callbacks++;
            }
            void bridge$forge$markLevelsDirty() { dirty++; }

            public static void verify(String scenario) throws Throwable {
                LifecycleFixture host = new LifecycleFixture();
                ServerLevel level = new ServerLevel("dim:new");
                var recorder = PrtsLevelLifecycle.server();
                if (scenario.equals("firstAccess")) {
                    check(recorder.current() != null, "first access has a snapshot");
                    check(recorder.current().loadedIds.isEmpty(), "first access has no loaded dimensions");
                    return;
                }
                recorder.retain(recorder.load("dim:existing"));
                var before = recorder.current();
                boolean startup = scenario.startsWith("startup");
                boolean fail = scenario.endsWith("Failure");
                boolean nonLevel = scenario.equals("nonLevel");
                boolean replacement = scenario.equals("startupReplacement");
                Object value = nonLevel ? new Object() : level;
                Object previous = new Object();
                Map<Object, Object> map = new LinkedHashMap<>() {
                    @Override
                    public Object put(Object key, Object value) {
                        check(recorder.current() == before, "a load must not be published before Map.put");
                        if (fail) throw FAILURE;
                        return super.put(key, value);
                    }
                };
                if (replacement) map.put(level.dimension(), previous);
                if (!startup && fail) host = new LifecycleFixture(map);
                if (scenario.equals("startupInitializerFailure")) Bukkit.server.failInit = true;
                boolean thrown = false;
                try {
                    if (startup || nonLevel) {
                        Object returned = host.arclight$worldInit(map, level.dimension(), value, null);
                        check(returned == (replacement ? previous : null), "Map.put result is unchanged");
                    } else {
                        host.addLevel(level);
                    }
                } catch (RuntimeException failure) {
                    check(failure == FAILURE, "the original host failure must escape");
                    thrown = true;
                }
                check(thrown == fail, "only failing boundaries throw");
                if (fail || nonLevel) {
                    check(recorder.current() == before, "a failed or non-level insert keeps the prior snapshot");
                    check(!host.levels.containsKey(level.dimension()), "failed dynamic insert changed no map");
                    if (startup) check(!map.containsKey(level.dimension()), "failed startup changed no map");
                } else {
                    check(recorder.current().loadedIds.equals(Set.of("dim:existing", level.id)),
                        "the new snapshot preserves other dimensions");
                    check(recorder.current().mark == PrtsLevelLifecycle.Mark.LOADED, "load mark");
                    check(!before.loadedIds.contains(level.id), "the old snapshot stays immutable");
                    if (startup) {
                        check(map.get(level.dimension()) == level, "startup inserted the level");
                    } else {
                        check(host.callbacks == 1 && host.dirty == 1, "dynamic callbacks run once");
                    }
                }
            }

            static void check(boolean condition, String message) {
                if (!condition) throw new AssertionError(message);
            }
            static class ChunkProgressListener {}
            record Dimension(String location) {}
            interface WorldBridge { Generator bridge$getGenerator(); }
            static class Generator { List<Object> getDefaultPopulators(CraftWorld world) { return List.of(); } }
            static class ServerLevel implements WorldBridge {
                final String id;
                final Dimension key;
                ServerLevel(String id) { this.id = id; key = new Dimension(id); }
                Dimension dimension() { return key; }
                Object getScoreboard() { return new Object(); }
                CraftWorld bridge$getWorldOrNull() { return null; }
                public Generator bridge$getGenerator() { return null; }
                Border getWorldBorder() { return new Border(); }
            }
            static class CraftWorld { List<Object> getPopulators() { return new ArrayList<>(); } }
            static class Border { void addListener(Object listener) {} }
            static class ArclightBorderChangeListener { static Object typed() { return new Object(); } }
            static class CraftServer { Object scoreboardManager; boolean failInit; }
            static class CraftScoreboardManager {
                CraftScoreboardManager(MinecraftServer server, Object scoreboard) {
                    if (Bukkit.server.failInit) throw FAILURE;
                }
            }
            static class Bukkit {
                static final CraftServer server = new CraftServer();
                static Object getServer() { return server; }
                static PluginManager getPluginManager() { return new PluginManager(); }
            }
            static class PluginManager { void callEvent(Object event) {} }
            record WorldInitEvent(CraftWorld world) {}
            static class WorldHookCompat {
                static void enterBukkitHook(String key, boolean enabled, Runnable action) {
                    if (enabled) action.run();
                }
            }
            static class DecorationOps {
                static DecorationOps callsite() { return new DecorationOps(); }
                Object invoke(Map<Object, Object> map, Object key, Object value) { return map.put(key, value); }
            }
        }
        """;
}
