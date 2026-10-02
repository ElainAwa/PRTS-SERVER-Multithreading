/* SPDX-License-Identifier: GPL-3.0-or-later */
/* Ported from the FeudalKings fork of Arclight (commit 1a39b26); see THIRD-PARTY.md. */
package io.izzel.arclight.common.prts.support;

import io.izzel.arclight.api.PluginPatcher;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Forces the server detection of that plugin to "no", so it registers commands through the Bukkit
 * API instead of a Paper path this server does not implement. */
public final class PrtsFaweCommandPatcher {

    private static final String COMMAND_MAP_METHOD = "getCommandMap";
    private static final String COMMAND_MAP_DESCRIPTOR = "()Lorg/bukkit/command/CommandMap;";
    private static final String LIBRARY = "io/papermc/lib/PaperLib";
    private static final String LIBRARY_METHOD = "isPaper";
    private static final String LIBRARY_DESCRIPTOR = "()Z";

    private PrtsFaweCommandPatcher() {
    }

    /** Replaces the server detection inside the command map lookup of the given class. */
    public static void handleFaweCommandRegistration(ClassNode node, PluginPatcher.ClassRepo classRepo) {
        for (MethodNode method : node.methods) {
            if (!COMMAND_MAP_METHOD.equals(method.name) || !COMMAND_MAP_DESCRIPTOR.equals(method.desc)) {
                continue;
            }
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call
                    && LIBRARY.equals(call.owner)
                    && LIBRARY_METHOD.equals(call.name)
                    && LIBRARY_DESCRIPTOR.equals(call.desc)) {
                    method.instructions.set(call, new InsnNode(Opcodes.ICONST_0));
                }
            }
            return;
        }
    }
}
