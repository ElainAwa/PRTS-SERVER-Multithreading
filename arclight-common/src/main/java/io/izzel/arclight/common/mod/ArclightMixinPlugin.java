package io.izzel.arclight.common.mod;

import io.izzel.arclight.common.mod.mixins.*;
import io.izzel.arclight.common.prts.support.PrtsTotemCureCallSite;
import io.izzel.arclight.mixin.MixinTools;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public class ArclightMixinPlugin implements IMixinConfigPlugin {

    private final List<MixinProcessor> preProcessors = List.of(
    );

    private final List<MixinProcessor> postProcessors = List.of(
        new RenameIntoProcessor(),
        new TransformAccessProcessor(),
        new CreateConstructorProcessor(),
        new InlineMethodProcessor(),
        new InlineFieldProcessor(),
        new InvokeSpecialProcessor(),
        // The shared configuration replaces the totem death protection method of the living entity
        // type, and with it a call the NeoForge patch of that type performs. Third party injectors
        // anchored on that call would stop applying, so the call is put back for the platform that
        // has it, before any other configuration is applied to the type.
        new PrtsTotemCureCallSite()
    );

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return ShouldApplyProcessor.shouldApply(mixinClassName);
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
        for (var processor : this.preProcessors) {
            processor.accept(targetClassName, targetClass, mixinInfo);
        }
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        for (var processor : this.postProcessors) {
            processor.accept(targetClassName, targetClass, mixinInfo);
        }
        MixinTools.onPostMixin(targetClass);
    }
}
