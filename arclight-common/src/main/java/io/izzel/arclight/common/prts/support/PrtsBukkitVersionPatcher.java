/* SPDX-License-Identifier: GPL-3.0-or-later */
/* Ported from the FeudalKings fork of Arclight (commit a349fff); see THIRD-PARTY.md. */
package io.izzel.arclight.common.prts.support;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Writes the static Paper version readout into the Bukkit facade. Mixin does not merge non-private
 * static methods, so the method is added from the post-apply step of the mixin configuration plugin.
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
     * Adds the readout when the target is the Bukkit facade and does not already carry the method.
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
