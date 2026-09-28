/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * Ported from the FeudalKings fork of Arclight, commit e0b9064c8d5669f7ad340babcf9144e288f6baca
 * ("fix(remapper): normalize CraftBukkit version packages").
 * See THIRD-PARTY.md.
 */
package io.izzel.arclight.common.prts.support;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PrtsCraftBukkitVersionRemapperTest {

    @Test
    void normalizesLegacyVersionPackages() {
        assertEquals("org/bukkit/craftbukkit/v/entity/CraftPlayer",
            PrtsCraftBukkitVersionRemapper.remapInternalName("org/bukkit/craftbukkit/v1_20_R1/entity/CraftPlayer"));
        assertEquals("org.bukkit.craftbukkit.v.entity.CraftPlayer",
            PrtsCraftBukkitVersionRemapper.remapBinaryName("org.bukkit.craftbukkit.v1_20_R1.entity.CraftPlayer"));
        assertEquals("java.lang.String", PrtsCraftBukkitVersionRemapper.remapBinaryName("java/lang/String"));
    }

    @Test
    void rewritesOwnersAndDescriptors() {
        ClassNode pluginClass = new ClassNode();
        pluginClass.name = "example/Plugin";
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "test", "()V", null, null);
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
            "org/bukkit/craftbukkit/v1_20_R1/util/CraftMagicNumbers", "getVersion",
            "()Lorg/bukkit/craftbukkit/v1_20_R1/CraftServer;", false));
        pluginClass.methods.add(method);

        PrtsCraftBukkitVersionRemapper.INSTANCE.handleClass(pluginClass, null, null);

        MethodInsnNode call = (MethodInsnNode) method.instructions.getFirst();
        assertEquals("org/bukkit/craftbukkit/v/util/CraftMagicNumbers", call.owner);
        assertEquals("()Lorg/bukkit/craftbukkit/v/CraftServer;", call.desc);
    }
}
