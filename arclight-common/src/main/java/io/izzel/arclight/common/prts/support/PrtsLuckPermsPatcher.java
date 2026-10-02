/* SPDX-License-Identifier: GPL-3.0-or-later */
/* Ported from the FeudalKings fork of Arclight (commit 36adbc7); see THIRD-PARTY.md. */
package io.izzel.arclight.common.prts.support;

import io.izzel.arclight.api.PluginPatcher;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Rewrites the static initializer of the permissible injector so its field handles come from
 * {@link PrtsLuckPermsCompat}. The plugin's own hard coded lookup fails on this server, which would
 * disable its permission injection.
 */
public final class PrtsLuckPermsPatcher {

    private static final String COMPAT_OWNER = "io/izzel/arclight/common/prts/support/PrtsLuckPermsCompat";
    private static final String FIELD_DESCRIPTOR = "Ljava/lang/reflect/Field;";

    private PrtsLuckPermsPatcher() {
    }

    /**
     * Replaces the static initializer of the given class when it is the permissible injector.
     */
    public static void handlePermissibleInjector(ClassNode node, PluginPatcher.ClassRepo classRepo) {
        for (MethodNode method : node.methods) {
            if (!"<clinit>".equals(method.name) || !"()V".equals(method.desc)) {
                continue;
            }
            method.instructions.clear();
            addFieldInitialization(method, node.name, "HUMAN_ENTITY_PERMISSIBLE_FIELD",
                "humanEntityPermissibleField");
            addFieldInitialization(method, node.name, "PERMISSIBLE_BASE_ATTACHMENTS_FIELD",
                "permissibleBaseAttachmentsField");
            method.instructions.add(new InsnNode(Opcodes.RETURN));
            method.tryCatchBlocks.clear();
            method.localVariables = null;
            method.maxStack = 1;
            method.maxLocals = 0;
            return;
        }
    }

    private static void addFieldInitialization(MethodNode method, String owner, String fieldName,
        String compatMethod) {
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, COMPAT_OWNER, compatMethod,
            "()" + FIELD_DESCRIPTOR, false));
        method.instructions.add(new FieldInsnNode(Opcodes.PUTSTATIC, owner, fieldName, FIELD_DESCRIPTOR));
    }
}
