/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.paper;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

// Compares a vendored paper-api 1.21.1 jar with the untransformed server classpath.
// A present signature is not a behavior test. Live mixin application is NOT_RUN.
class PaperApiSignatureInventoryTest {

    private static final String MISSING = "MISSING";
    private static final String PRESENT = "PRESENT_BEHAVIOR_UNTESTED";
    private static final String LIVE_NOT_RUN = "NOT_RUN";

    private static final Map<String, String> EXPECTED = new LinkedHashMap<>();
    private static final Map<String, String> LIVE = new LinkedHashMap<>();
    private static final java.util.Set<String> OUTSIDE_VENDORED_JAR = new java.util.HashSet<>();

    static {
        expect("org/bukkit/plugin/PluginDescriptionFile", "getAPIVersion", "()Ljava/lang/String;", PRESENT);
        expect("io/papermc/paper/plugin/bootstrap/PluginBootstrap", "bootstrap",
            "(Lio/papermc/paper/plugin/bootstrap/BootstrapContext;)V", MISSING);
        expect("org/bukkit/event/player/PlayerJoinEvent", "joinMessage", "()Lnet/kyori/adventure/text/Component;", MISSING);
        expect("io/papermc/paper/event/player/AsyncChatEvent", "getHandlers", "()Lorg/bukkit/event/HandlerList;", MISSING);
        expect("org/bukkit/event/block/BlockBreakEvent", "isCancelled", "()Z", PRESENT);
        expect("org/bukkit/scheduler/BukkitScheduler", "runTask",
            "(Lorg/bukkit/plugin/Plugin;Ljava/lang/Runnable;)Lorg/bukkit/scheduler/BukkitTask;", PRESENT);
        expect("org/bukkit/command/PluginCommand", "getPlugin", "()Lorg/bukkit/plugin/Plugin;", PRESENT);
        expect("net/kyori/adventure/text/Component", "text", "(Ljava/lang/String;)Lnet/kyori/adventure/text/TextComponent;", MISSING);
        outsideVendoredJar("net/kyori/adventure/text/Component", "text", "(Ljava/lang/String;)Lnet/kyori/adventure/text/TextComponent;");
        expect("org/bukkit/inventory/meta/components/FoodComponent", "getNutrition", "()I", PRESENT);
        expect("org/bukkit/entity/Player", "getPlayerProfile", "()Lcom/destroystokyo/paper/profile/PlayerProfile;", MISSING);
        expect("org/bukkit/World", "getChunkAtAsync", "(II)Ljava/util/concurrent/CompletableFuture;", MISSING);
        expect("org/bukkit/plugin/PluginLoader", "loadPlugin",
            "(Ljava/io/File;)Lorg/bukkit/plugin/Plugin;", PRESENT);
        expect("org/bukkit/Bukkit", "getMinecraftVersion", "()Ljava/lang/String;", MISSING);
    }

    @Test
    void paperApiSignaturesMatchTheInventory() throws Exception {
        List<Path> paper = classpath("paper.inventory.paperClasspath");
        List<Path> server = classpath("paper.inventory.serverClasspath");
        assertFalse(paper.isEmpty(), "vendored paper-api jar is missing");
        Map<String, List<String>> paperIndex = index(paper);
        Map<String, List<String>> serverIndex = index(server);
        Map<String, String> actual = new LinkedHashMap<>();
        for (String key : EXPECTED.keySet()) {
            int split = key.indexOf(' ');
            String type = key.substring(0, split);
            String member = key.substring(split + 1);
            boolean inPaperJar = paperIndex.getOrDefault(type, List.of()).contains(member);
            if (!inPaperJar && !OUTSIDE_VENDORED_JAR.contains(key)) {
                throw new AssertionError("probe is not in the vendored paper-api jar: " + key);
            }
            boolean onServer = serverIndex.getOrDefault(type, List.of()).contains(member);
            actual.put(key, onServer ? PRESENT : MISSING);
        }
        assertEquals(EXPECTED, actual);
    }

    @Test
    void liveMergeWasNotRun() {
        assertEquals(LIVE, liveMarks());
    }

    private static void expect(String type, String name, String descriptor, String mark) {
        String key = type + " " + name + descriptor;
        EXPECTED.put(key, mark);
        LIVE.put(key, LIVE_NOT_RUN);
    }

    private static void outsideVendoredJar(String type, String name, String descriptor) {
        OUTSIDE_VENDORED_JAR.add(type + " " + name + descriptor);
    }

    private static Map<String, String> liveMarks() {
        Map<String, String> marks = new LinkedHashMap<>();
        for (String key : EXPECTED.keySet()) {
            marks.put(key, LIVE_NOT_RUN);
        }
        return marks;
    }

    private static List<Path> classpath(String property) {
        String raw = System.getProperty(property);
        if (raw == null || raw.isBlank()) {
            throw new AssertionError("missing " + property);
        }
        List<Path> paths = new ArrayList<>();
        for (String part : raw.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            if (!part.isBlank()) {
                paths.add(Path.of(part));
            }
        }
        return paths;
    }

    private static Map<String, List<String>> index(List<Path> roots) throws IOException {
        Map<String, List<String>> found = new LinkedHashMap<>();
        for (Path root : roots) {
            if (!Files.exists(root)) {
                continue;
            }
            if (Files.isDirectory(root)) {
                readTree(root, root, found);
            } else if (root.toString().endsWith(".jar")) {
                readJar(root, found);
            }
        }
        return found;
    }

    private static void readTree(Path root, Path dir, Map<String, List<String>> found) throws IOException {
        try (var stream = Files.list(dir)) {
            for (Path child : stream.toList()) {
                if (Files.isDirectory(child)) {
                    readTree(root, child, found);
                } else if (child.toString().endsWith(".class")) {
                    String type = root.relativize(child).toString().replace('\\', '/');
                    type = type.substring(0, type.length() - ".class".length());
                    found.computeIfAbsent(type, key -> new ArrayList<>()).addAll(members(Files.readAllBytes(child)));
                }
            }
        }
    }

    private static void readJar(Path jar, Map<String, List<String>> found) throws IOException {
        try (JarFile file = new JarFile(jar.toFile())) {
            var entries = file.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) {
                    continue;
                }
                String type = entry.getName().substring(0, entry.getName().length() - ".class".length());
                try (InputStream in = file.getInputStream(entry)) {
                    found.computeIfAbsent(type, key -> new ArrayList<>()).addAll(members(in.readAllBytes()));
                }
            }
        }
    }

    private static List<String> members(byte[] bytes) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        if (in.readInt() != 0xCAFEBABE) {
            return List.of();
        }
        in.readUnsignedShort();
        in.readUnsignedShort();
        int count = in.readUnsignedShort();
        String[] utf = new String[count];
        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1 -> utf[i] = in.readUTF();
                case 7, 8, 16, 19, 20 -> in.readUnsignedShort();
                case 15 -> {
                    in.readUnsignedByte();
                    in.readUnsignedShort();
                }
                case 3, 4, 9, 10, 11, 12, 17, 18 -> in.readInt();
                case 5, 6 -> {
                    in.readLong();
                    i++;
                }
                default -> throw new IOException("unknown constant tag " + tag);
            }
        }
        in.readUnsignedShort();
        in.readUnsignedShort();
        in.readUnsignedShort();
        int interfaces = in.readUnsignedShort();
        for (int i = 0; i < interfaces; i++) {
            in.readUnsignedShort();
        }
        skipMembers(in);
        int methods = in.readUnsignedShort();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < methods; i++) {
            in.readUnsignedShort();
            String name = utf[in.readUnsignedShort()];
            String descriptor = utf[in.readUnsignedShort()];
            names.add(name + descriptor);
            skipAttributes(in);
        }
        return names;
    }

    private static void skipMembers(DataInputStream in) throws IOException {
        int count = in.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            in.readUnsignedShort();
            in.readUnsignedShort();
            in.readUnsignedShort();
            skipAttributes(in);
        }
    }

    private static void skipAttributes(DataInputStream in) throws IOException {
        int count = in.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            in.readUnsignedShort();
            int length = in.readInt();
            in.skipNBytes(length);
        }
    }
}
