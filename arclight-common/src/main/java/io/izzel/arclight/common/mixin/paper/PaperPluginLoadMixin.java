/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.mixin.paper;

import io.izzel.arclight.common.mod.server.ArclightServer;
import io.izzel.arclight.common.paper.PaperPluginJars;
import org.bukkit.plugin.InvalidPluginException;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPluginLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.File;
import java.io.IOException;

@Mixin(value = JavaPluginLoader.class, remap = false)
public class PaperPluginLoadMixin {
    // Skips a jar that contains paper-plugin.yml and no plugin.yml.
    // The loader would otherwise report a missing plugin.yml and hide that refusal.
    // loadPlugin(File) is about to open this same jar.
    // An unsupported jar throws InvalidPluginException. A jar that cannot be read is logged and left here.

    @Inject(method = "loadPlugin(Ljava/io/File;)Lorg/bukkit/plugin/Plugin;", at = @At("HEAD"))
    private void arclight$refusePaperOnly(File file, CallbackInfoReturnable<Plugin> callback)
            throws InvalidPluginException {
        if (file == null) {
            return;
        }
        try {
            PaperPluginJars.refusePaperOnly(file.toPath());
        } catch (IOException ex) {
            ArclightServer.LOGGER.warn("Could not read plugin jar {}, leaving it to the loader", file, ex);
            return;
        }
    }
}
