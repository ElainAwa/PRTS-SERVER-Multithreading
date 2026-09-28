/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * Ported from the FeudalKings fork of Arclight, commit 36adbc7b4a58afe79c6e881dc4afbfbcaa7d2c94
 * ("fix(compat): support LuckPerms permissible injection").
 * See THIRD-PARTY.md.
 */
package io.izzel.arclight.common.prts.support;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PrtsLuckPermsPatcherTest {

    @Test
    void initializesPermissibleFieldsThroughTheServer() {
        ClassNode injector = new ClassNode();
        injector.name = "me/lucko/luckperms/bukkit/inject/permissible/PermissibleInjector";
        MethodNode initializer = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        initializer.instructions.add(new InsnNode(Opcodes.RETURN));
        injector.methods.add(initializer);

        PrtsLuckPermsPatcher.handlePermissibleInjector(injector, null);

        MethodInsnNode humanField = (MethodInsnNode) initializer.instructions.getFirst();
        FieldInsnNode humanStore = (FieldInsnNode) humanField.getNext();
        MethodInsnNode attachmentsField = (MethodInsnNode) humanStore.getNext();
        FieldInsnNode attachmentsStore = (FieldInsnNode) attachmentsField.getNext();
        assertEquals("io/izzel/arclight/common/prts/support/PrtsLuckPermsCompat", humanField.owner);
        assertEquals("humanEntityPermissibleField", humanField.name);
        assertEquals("HUMAN_ENTITY_PERMISSIBLE_FIELD", humanStore.name);
        assertEquals("permissibleBaseAttachmentsField", attachmentsField.name);
        assertEquals("PERMISSIBLE_BASE_ATTACHMENTS_FIELD", attachmentsStore.name);
        assertEquals(Opcodes.RETURN, initializer.instructions.getLast().getOpcode());
    }
}
