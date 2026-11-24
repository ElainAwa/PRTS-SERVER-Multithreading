package io.izzel.arclight.boot.asm;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Diagnostic-only segment trace inside the phase-B stage and the vanilla travel method. Each
 * probe loads only its own arguments (this and a tag), so the host stack and the original writes
 * are untouched. The descriptor is exposed for the build-time signature check. */
public final class PrtsPhaseBTrace implements Implementer {

    private static final String OWNER = "net/minecraft/world/entity/LivingEntity";
    private static final String HELPER =
        "io/izzel/arclight/common/prts/kernel/domain/entity/owner/PhaseBSegmentDiagnostic";
    public static final String NOTE_DESC = "(Lnet/minecraft/world/entity/Entity;Ljava/lang/String;)V";

    @Override
    public boolean processClass(ClassNode node) {
        if (!OWNER.equals(node.name)) return false;
        boolean changed = false;
        for (MethodNode method : node.methods) {
            boolean phaseB = "prts$aiStepPhaseB".equals(method.name);
            boolean travel = "travel".equals(method.name)
                && "(Lnet/minecraft/world/phys/Vec3;)V".equals(method.desc);
            if (!phaseB && !travel) continue;
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; 
                 insn = insn.getNext()) {
                String tag = tag(insn, phaseB);
                if (tag == null) continue;
                InsnList probe = new InsnList();
                probe.add(new VarInsnNode(Opcodes.ALOAD, 0));
                probe.add(new LdcInsnNode(tag));
                probe.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "note",
                    NOTE_DESC, false));
                method.instructions.insertBefore(insn, probe);
                changed = true;
                if ("beforeTravel".equals(tag) || "beforeMove".equals(tag)) {
                    String afterTag = "beforeTravel".equals(tag) ? "afterTravel" : "afterMove";
                    InsnList after = new InsnList();
                    after.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    after.add(new LdcInsnNode(afterTag));
                    after.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "note",
                        NOTE_DESC, false));
                    method.instructions.insert(insn, after);
                }
                method.maxStack = Math.max(method.maxStack, 8);
            }
        }
        return changed;
    }

    private static String tag(AbstractInsnNode insn, boolean phaseB) {
        if (phaseB && insn instanceof FieldInsnNode field
            && field.getOpcode() == Opcodes.GETFIELD
            && OWNER.equals(field.owner) && "jumping".equals(field.name)) return "jump";
        if (phaseB && insn instanceof MethodInsnNode call
            && call.getOpcode() == Opcodes.INVOKEVIRTUAL
            && "travel".equals(call.name)
            && "(Lnet/minecraft/world/phys/Vec3;)V".equals(call.desc)) return "beforeTravel";
        if (!phaseB && insn instanceof MethodInsnNode call
            && call.getOpcode() == Opcodes.INVOKEVIRTUAL
            && "move".equals(call.name)
            && "(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V".equals(call.desc))
            return "beforeMove";
        return null;
    }
}