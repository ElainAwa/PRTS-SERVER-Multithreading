/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * Ported from the FeudalKings fork of Arclight, commit 36adbc7b4a58afe79c6e881dc4afbfbcaa7d2c94
 * ("fix(compat): support LuckPerms permissible injection").
 * See THIRD-PARTY.md.
 */
package io.izzel.arclight.common.prts.support;

import io.izzel.arclight.api.PluginPatcher;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Rewrites the static initializer of the plugin class that injects a custom permissible so that it
 * takes its field handles from {@link PrtsLuckPermsCompat} instead of resolving them itself.
 *
 * <p>The plugin resolves the handles with a hard coded field name and a plain reflective lookup. On
 * this server the permissible of a human entity is held by a field the plugin does not expect, so
 * the lookup fails and the plugin disables its permission injection. Replacing the initializer
 * keeps the plugin on its own code path while the handles come from the server, which is the only
 * side that knows the real field names.</p>
 */
public final class PrtsLuckPermsPatcher {

    private static final String COMPAT_OWNER = "io/izzel/arclight/common/prts/support/PrtsLuckPermsCompat";
    private static final String FIELD_DESCRIPTOR = "Ljava/lang/reflect/Field;";

    private PrtsLuckPermsPatcher() {
    }

    /**
     * Replaces the static initializer of the given class when it is the permissible injector.
     *
     * @param node     the class being loaded
     * @param classRepo repository of the classes the plugin can see, unused here
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
