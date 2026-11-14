/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.paper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PaperPluginJarsTest {

    @TempDir
    Path tmp;

    @Test
    void pluginYmlStaysWithBukkit() throws Exception {
        assertEquals(PaperPluginJars.BUKKIT, PaperPluginJars.classify(jar("bukkit.jar", Map.of(
            "plugin.yml", "name: Sample\n"))));
    }

    @Test
    void paperPluginYmlAloneIsUnsupported() throws Exception {
        assertEquals(PaperPluginJars.UNSUPPORTED, PaperPluginJars.classify(jar("paper.jar", Map.of(
            "paper-plugin.yml", "name: Sample\n"))));
    }

    @Test
    void aJarWithNeitherFileIsNotAPlugin() throws Exception {
        assertEquals(PaperPluginJars.NOT_A_PLUGIN, PaperPluginJars.classify(jar("empty.jar", Map.of(
            "readme.txt", "no plugin\n"))));
    }

    private Path jar(String name, Map<String, String> entries) throws Exception {
        Path path = tmp.resolve(name);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(path))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey()));
                out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return path;
    }
}
