/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.paper;

import org.bukkit.plugin.InvalidPluginException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.jar.JarFile;

/**
 * Classifies a plugin jar. A paper-plugin.yml without plugin.yml is refused.
 * The bootstrap callback is not called.
 */
public final class PaperPluginJars {

    public static final String BUKKIT = "BUKKIT";
    public static final String UNSUPPORTED = "UNSUPPORTED";
    public static final String NOT_A_PLUGIN = "NOT_A_PLUGIN";

    private PaperPluginJars() {
    }

    public static String classify(Path jar) throws IOException {
        boolean pluginYml;
        boolean paperYml;
        try (JarFile file = new JarFile(jar.toFile())) {
            pluginYml = file.getJarEntry("plugin.yml") != null;
            paperYml = file.getJarEntry("paper-plugin.yml") != null;
        }
        if (pluginYml) {
            return BUKKIT;
        }
        if (paperYml) {
            return UNSUPPORTED;
        }
        return NOT_A_PLUGIN;
    }

    public static void refusePaperOnly(Path jar) throws IOException, InvalidPluginException {
        if (UNSUPPORTED.equals(classify(jar))) {
            throw new InvalidPluginException(UNSUPPORTED);
        }
    }
}
