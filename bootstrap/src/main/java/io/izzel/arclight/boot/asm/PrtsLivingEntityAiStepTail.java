package io.izzel.arclight.boot.asm;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Splits LivingEntity.aiStep once, in this single pass: the tail after serverAiStep becomes the
 * phase-A stage, then the part from the jump profiler section onward becomes the phase-B stage. A
 * program entry at the boundary lets a batch run phase A, its read wave, then phase B on the same
 * seat. The boundary anchor is the profiler pop before push("jump") so phase B owns a balanced
 * push/pop pair; anchoring on the push itself would strand its pop in phase A.
 */
public final class PrtsLivingEntityAiStepTail implements Implementer {

    private static final String OWNER = "net/minecraft/world/entity/LivingEntity";
    private static final String TAIL = "prts$aiStepTail";
    private static final String PHASE_A = "prts$aiStepPhaseA";
    private static final String PHASE_B = "prts$aiStepPhaseB";
    private static final String STAGES =
        "io/izzel/arclight/common/prts/kernel/domain/entity/owner/LivingEntityAiStepStages";
    private static final String PROGRAM =
        "io/izzel/arclight/common/prts/kernel/domain/entity/owner/AiStepPhaseProgram";

    @Override
    public boolean processClass(ClassNode node) {
        if (!OWNER.equals(node.name)) {
            return false;
        }
        for (MethodNode method : node.methods) {
            if (PHASE_A.equals(method.name) || PHASE_B.equals(method.name)) {
                return false;
            }
        }
        boolean tail = TailRegionExtractor.extract(node, "aiStep", "()V",
            call -> call.getOpcode() == Opcodes.INVOKEVIRTUAL
                && "serverAiStep".equals(call.name) && "()V".equals(call.desc),
            TAIL);
        if (!tail) {
            return false;
        }
        MethodNode host = null;
        for (MethodNode method : node.methods) {
            if (TAIL.equals(method.name)) {
                host = method;
            }
        }
        if (host == null) {
            return false;
        }
        boolean phaseB = TailRegionExtractor.extractProgram(node, TAIL, host.desc,
            PrtsLivingEntityAiStepTail::isJumpSectionBoundary, PHASE_B, STAGES, PROGRAM);
        if (!phaseB) {
            return false;
        }
        for (MethodNode method : node.methods) {
            if (TAIL.equals(method.name)) {
                method.name = PHASE_A;
                method.access = method.access & ~Opcodes.ACC_PRIVATE | Opcodes.ACC_PUBLIC;
            }
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; ) {
                AbstractInsnNode next = insn.getNext();
                if (insn instanceof MethodInsnNode call && TAIL.equals(call.name)) {
                    call.name = PHASE_A;
                }
                insn = next;
            }
        }
        return true;
    }

    /** The profiler pop whose next real instructions load "jump" and call push; the pop is kept in
     * phase A and the following push and its matching pop both move into phase B. */
    private static boolean isJumpSectionBoundary(MethodInsnNode call) {
        if (call.getOpcode() != Opcodes.INVOKEINTERFACE
            && call.getOpcode() != Opcodes.INVOKEVIRTUAL) {
            return false;
        }
        if (!"pop".equals(call.name) || !"()V".equals(call.desc)) {
            return false;
        }
        AbstractInsnNode cursor = call.getNext();
        boolean jump = false;
        int budget = 8;
        while (cursor != null && budget-- > 0) {
            if (cursor instanceof LdcInsnNode literal && "jump".equals(literal.cst)) {
                jump = true;
            }
            if (jump && cursor instanceof MethodInsnNode push && "push".equals(push.name)) {
                return true;
            }
            cursor = cursor.getNext();
        }
        return false;
    }
}
