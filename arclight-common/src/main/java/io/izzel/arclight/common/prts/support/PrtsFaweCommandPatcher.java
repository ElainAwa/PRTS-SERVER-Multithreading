/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * Ported from the FeudalKings fork of Arclight, commit 1a39b264f9c389db6afc6f448944c0a4a5ce347e
 * ("fix(compat): patch FAWE command registration").
 * See THIRD-PARTY.md.
 */
package io.izzel.arclight.common.prts.support;

import io.izzel.arclight.api.PluginPatcher;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Makes the command registration of the plugin ask for the command map of this server.
 *
 * <p>The plugin decides which command map to use by asking a library whether the server is a Paper
 * server, and takes a path this server does not implement when the answer is yes. The answer is
 * forced to no so that the plugin registers its commands through the Bukkit API like it does on any
 * other server.</p>
 */
public final class PrtsFaweCommandPatcher {

    private static final String COMMAND_MAP_METHOD = "getCommandMap";
    private static final String COMMAND_MAP_DESCRIPTOR = "()Lorg/bukkit/command/CommandMap;";
    private static final String LIBRARY = "io/papermc/lib/PaperLib";
    private static final String LIBRARY_METHOD = "isPaper";
    private static final String LIBRARY_DESCRIPTOR = "()Z";

    private PrtsFaweCommandPatcher() {
    }

    /**
     * Replaces the server detection inside the command map lookup of the given class.
     *
     * @param node      the class being loaded
     * @param classRepo repository of the classes the plugin can see, unused here
     */
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
