/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts;

import io.izzel.arclight.common.mod.mixins.ShouldApplyProcessor;
import io.izzel.arclight.common.prts.support.PrtsBukkitVersionPatcher;
import io.izzel.arclight.common.prts.support.PrtsSeams;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Mixin plugin shared by every PRTS category: the category is derived from the mixin package, so each
 * {@code prts-*.mixins.json} gets its own master switch.
 */
public class PrtsMixinPlugin implements IMixinConfigPlugin {

    private static final String ROOT = "io.izzel.arclight.common.prts";

    private String category = PrtsSwitches.FIXES;

    @Override
    public void onLoad(String mixinPackage) {
        this.category = categoryOf(mixinPackage);
    }

    /**
     * Maps a mixin package to its category name; unknown packages fall back to {@link PrtsSwitches#FIXES}.
     */
    static String categoryOf(String mixinPackage) {
        if (mixinPackage == null || !mixinPackage.startsWith(ROOT + ".")) {
            return PrtsSwitches.FIXES;
        }
        String rest = mixinPackage.substring(ROOT.length() + 1);
        int dot = rest.indexOf('.');
        String head = dot < 0 ? rest : rest.substring(0, dot);
        if ("optional".equals(head)) {
            String tail = dot < 0 ? "" : rest.substring(dot + 1);
            int next = tail.indexOf('.');
            String layer = next < 0 ? tail : tail.substring(0, next);
            return layer.isEmpty() ? "optional" : "optional-" + layer;
        }
        return head;
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        // A platform-declaring member is dropped on the other platform, so it may refer to its own types.
        boolean apply = PrtsSwitches.enabled(this.category)
            && ShouldApplyProcessor.shouldApply(mixinClassName);
        // The gate for a kernel seam, recorded so a refused seam cannot be published as a green readout.
        PrtsSeams.noteDecision(mixinClassName, apply);
        return apply;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        PrtsSeams.noteApplied(mixinClassName);
        // API members that cannot be a mixin are written into the target here, the last open point.
        PrtsBukkitVersionPatcher.patch(targetClassName, targetClass);
    }
}
