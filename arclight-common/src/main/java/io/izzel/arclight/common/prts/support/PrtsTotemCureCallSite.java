/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * Ported from the FeudalKings fork of Arclight, commit 47909e7a303684dcc593758e3f88cdefe0d4912b
 * ("fix: preserve totem call site and add neoforge compileOnly").
 * Re-expressed as a post-apply bytecode step so the Fabric build, which shares the same mixin
 * configuration but has no such method, keeps working. See THIRD-PARTY.md.
 */
package io.izzel.arclight.common.prts.support;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.MixinProcessor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Restores the totem cure call site inside the overridden totem death protection method.
 *
 * <p>The shared mixin configuration replaces that method wholesale, so the call to
 * {@code removeEffectsCuredBy} that the NeoForge patch of the target class performs is lost. Third
 * party injectors that anchor on that call then fail to apply. The call has to be a real
 * instruction of the method itself: an injected handler call would live in a synthetic method of
 * its own, where no injector can see it. Mixin only merges code inside an override, and an
 * override that refers to a NeoForge type cannot be shared with the Fabric build, which has neither
 * the type nor the method. The instruction is therefore added, for NeoForge only, in the post-apply
 * step of the mixin configuration that owns the override, before any other configuration is
 * applied to the class.</p>
 */
public final class PrtsTotemCureCallSite implements MixinProcessor {

    private static final String LIVING_ENTITY = "net.minecraft.world.entity.LivingEntity";
    private static final String TOTEM_METHOD = "checkTotemDeathProtection";
    private static final String TOTEM_DESCRIPTOR = "(Lnet/minecraft/world/damagesource/DamageSource;)Z";
    private static final String CURE_OWNER = "net/neoforged/neoforge/common/EffectCures";
    private static final String CURE_FIELD = "PROTECTED_BY_TOTEM";
    private static final String CURE_DESCRIPTOR = "Lnet/neoforged/neoforge/common/EffectCure;";
    private static final String CURE_METHOD = "removeEffectsCuredBy";
    private static final String CURE_METHOD_DESCRIPTOR = "(" + CURE_DESCRIPTOR + ")Z";
    private static final String ANCHOR_METHOD = "removeAllEffects";

    @Override
    public void accept(String className, ClassNode classNode, IMixinInfo mixinInfo) {
        if (!LIVING_ENTITY.equals(className) || ArclightPlatform.current() != ArclightPlatform.NEOFORGE) {
            return;
        }
        MethodNode method = findTotemMethod(classNode);
        if (method == null || hasCureCall(method)) {
            return;
        }
        AbstractInsnNode anchor = findAnchor(method);
        if (anchor == null) {
            return;
        }
        // The name handed to the plugin is the binary name, while an instruction owner has to be
        // the internal name.
        String owner = className.replace('.', '/');
        // this.removeEffectsCuredBy(EffectCures.PROTECTED_BY_TOTEM); the result is discarded, so
        // the sequence leaves the operand stack exactly as it was found.
        method.instructions.insertBefore(anchor, new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.insertBefore(anchor,
            new FieldInsnNode(Opcodes.GETSTATIC, CURE_OWNER, CURE_FIELD, CURE_DESCRIPTOR));
        method.instructions.insertBefore(anchor,
            new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, CURE_METHOD, CURE_METHOD_DESCRIPTOR, false));
        method.instructions.insertBefore(anchor, new InsnNode(Opcodes.POP));
        method.maxStack = Math.max(method.maxStack, 2);
    }

    private static MethodNode findTotemMethod(ClassNode classNode) {
        for (MethodNode method : classNode.methods) {
            if (TOTEM_METHOD.equals(method.name) && TOTEM_DESCRIPTOR.equals(method.desc)) {
                return method;
            }
        }
        return null;
    }

    private static boolean hasCureCall(MethodNode method) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && CURE_METHOD.equals(call.name)) {
                return true;
            }
        }
        return false;
    }

    private static AbstractInsnNode findAnchor(MethodNode method) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && ANCHOR_METHOD.equals(call.name)) {
                return instruction;
            }
        }
        return null;
    }
}
