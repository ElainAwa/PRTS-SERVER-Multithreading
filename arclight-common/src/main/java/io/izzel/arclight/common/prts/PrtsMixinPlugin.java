package io.izzel.arclight.common.prts;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * One mixin plugin shared by every PRTS category. The category is derived from the mixin package
 * ({@code io.izzel.arclight.common.prts.<category>[.<layer>]}), so each {@code prts-*.mixins.json}
 * gets its own master switch without a plugin class per category.
 */
public class PrtsMixinPlugin implements IMixinConfigPlugin {

    private static final String ROOT = "io.izzel.arclight.common.prts";

    private String category = PrtsSwitches.FIXES;

    @Override
    public void onLoad(String mixinPackage) {
        this.category = categoryOf(mixinPackage);
    }

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
        return PrtsSwitches.enabled(this.category);
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
    }
}
