/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * Ported from the FeudalKings fork of Arclight, commit a349fffb97b9678b20b65a0eb1f290cc7912606f
 * ("add common Paper API compatibility").
 * See THIRD-PARTY.md.
 */
package io.izzel.arclight.common.prts.support;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Adds the static Paper version readout to the Bukkit facade.
 *
 * <p>The readout has to be a static member of the facade, and Mixin does not merge non-private
 * static methods. The method is therefore written into the target class once, from the post-apply
 * step of the PRTS mixin configuration plugin.</p>
 */
public final class PrtsBukkitVersionPatcher {

    private static final String BUKKIT_CLASS = "org.bukkit.Bukkit";
    private static final String BUKKIT = "org/bukkit/Bukkit";
    private static final String SERVER = "org/bukkit/Server";
    private static final String METHOD = "getMinecraftVersion";
    private static final String DESCRIPTOR = "()Ljava/lang/String;";

    private PrtsBukkitVersionPatcher() {
    }

    /**
     * Adds the version readout when the given target is the Bukkit facade and lacks the method.
     *
     * @param targetClassName name of the class the mixin was applied to, dotted or internal
     * @param targetClass     the class the mixin was applied to
     */
    public static void patch(String targetClassName, ClassNode targetClass) {
        if (targetClassName == null
            || !BUKKIT_CLASS.equals(targetClassName.replace('/', '.'))
            || hasMethod(targetClass)) {
            return;
        }
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            METHOD, DESCRIPTOR, null, null);
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
            BUKKIT, "getServer", "()L" + SERVER + ";", false));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,
            SERVER, METHOD, DESCRIPTOR, true));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        method.maxStack = 1;
        method.maxLocals = 0;
        targetClass.methods.add(method);
    }

    private static boolean hasMethod(ClassNode targetClass) {
        for (MethodNode method : targetClass.methods) {
            if (METHOD.equals(method.name) && DESCRIPTOR.equals(method.desc)) {
                return true;
            }
        }
        return false;
    }
}
