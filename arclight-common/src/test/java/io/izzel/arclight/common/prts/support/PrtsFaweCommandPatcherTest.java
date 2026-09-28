/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * Ported from the FeudalKings fork of Arclight, commit 1a39b264f9c389db6afc6f448944c0a4a5ce347e
 * ("fix(compat): patch FAWE command registration").
 * See THIRD-PARTY.md.
 */
package io.izzel.arclight.common.prts.support;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PrtsFaweCommandPatcherTest {

    @Test
    void forcesTheCommandMapLookupOntoTheBukkitPath() {
        ClassNode registration = new ClassNode();
        registration.name = "com/sk89q/bukkit/util/CommandRegistration";
        MethodNode getCommandMap = new MethodNode(Opcodes.ACC_PRIVATE, "getCommandMap",
            "()Lorg/bukkit/command/CommandMap;", null, null);
        getCommandMap.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
            "io/papermc/lib/PaperLib", "isPaper", "()Z", false));
        getCommandMap.instructions.add(new InsnNode(Opcodes.IRETURN));
        registration.methods.add(getCommandMap);

        PrtsFaweCommandPatcher.handleFaweCommandRegistration(registration, null);

        assertEquals(Opcodes.ICONST_0, getCommandMap.instructions.getFirst().getOpcode());
    }
}
