package io.izzel.arclight.boot.asm;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Diagnostic-only: records the actual PUTFIELD site that writes LivingEntity.zza. A duplicated
 * (this, value) pair is handed to the helper, so the original write keeps its operands and its
 * position; the stored value and its timing never change. */
public final class PrtsZzaWriteRecorder implements Implementer {

    private static final String OWNER = "net/minecraft/world/entity/LivingEntity";
    private static final String HELPER =
        "io/izzel/arclight/common/prts/kernel/domain/entity/owner/ZzaWriteDiagnostic";

    @Override
    public boolean processClass(ClassNode node) {
        if (!OWNER.equals(node.name)) return false;
        boolean changed = false;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; 
                 insn = insn.getNext()) {
                if (!(insn instanceof FieldInsnNode field)
                    || field.getOpcode() != Opcodes.PUTFIELD
                    || !OWNER.equals(field.owner) || !"zza".equals(field.name)
                    || !"F".equals(field.desc)) continue;
                InsnList probe = new InsnList();
                probe.add(new InsnNode(Opcodes.DUP2));
                probe.add(new LdcInsnNode(method.name));
                probe.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "note",
                    "(L" + OWNER + ";FLjava/lang/String;)V", false));
                method.instructions.insertBefore(insn, probe);
                // DUP2 needs two extra stack slots while the helper is called.
                method.maxStack = Math.max(method.maxStack, 6);
                changed = true;
            }
        }
        return changed;
    }
}