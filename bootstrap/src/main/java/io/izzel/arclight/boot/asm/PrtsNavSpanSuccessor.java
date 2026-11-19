package io.izzel.arclight.boot.asm;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Extracts the successor block of {@code Mob.serverAiStep} after its navigation call into one
 * callable stage method, so a later owner strategy can drive prefix, span and successor through the
 * same implementation instead of a second copy of the host tail. The block is straight-line code
 * over the host locals (this, profiler) and has no exception table, so it can be moved without
 * changing branch, exception or profiler order; the host method keeps its frame and only calls the
 * stage once at the same position.
 */
public final class PrtsNavSpanSuccessor implements Implementer {

    private static final String OWNER = "net/minecraft/world/entity/Mob";
    private static final String HOST = "serverAiStep";
    private static final String HOST_DESC = "()V";
    private static final String NAV = "net/minecraft/world/entity/ai/navigation/PathNavigation";
    private static final String STAGE = "prts$serverAiStepSuccessor";
    private static final String STAGE_DESC = "(Lnet/minecraft/util/profiling/ProfilerFiller;)V";
    private static final String PROFILER = "net/minecraft/util/profiling/ProfilerFiller";

    @Override
    public boolean processClass(ClassNode node) {
        if (!OWNER.equals(node.name)) {
            return false;
        }
        MethodNode host = null;
        for (MethodNode method : node.methods) {
            if (STAGE.equals(method.name)) {
                return false;
            }
            if (HOST.equals(method.name) && HOST_DESC.equals(method.desc)) {
                host = method;
            }
        }
        if (host == null) {
            return false;
        }
        AbstractInsnNode[] instructions = host.instructions.toArray();
        int anchor = -1;
        for (int index = 0; index < instructions.length; index++) {
            AbstractInsnNode node1 = instructions[index];
            if (node1 instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                && NAV.equals(call.owner) && "tick".equals(call.name) && "()V".equals(call.desc)) {
                anchor = index;
                break;
            }
        }
        if (anchor < 0) {
            return false;
        }
        List<AbstractInsnNode> tail = new ArrayList<>();
        boolean terminated = false;
        for (int index = anchor + 1; index < instructions.length; index++) {
            AbstractInsnNode node1 = instructions[index];
            if (node1 instanceof LabelNode || node1 instanceof LineNumberNode) {
                continue;
            }
            if (node1 instanceof FrameNode || node1 instanceof JumpInsnNode
                || node1 instanceof TableSwitchInsnNode || node1 instanceof LookupSwitchInsnNode) {
                return false;
            }
            if (node1.getOpcode() == Opcodes.RETURN) {
                terminated = true;
                break;
            }
            if (node1 instanceof VarInsnNode variable && variable.var >= 2) {
                return false;
            }
            tail.add(node1);
        }
        if (!terminated || tail.isEmpty()) {
            return false;
        }
        MethodNode stage = new MethodNode(Opcodes.ACC_PRIVATE, STAGE, STAGE_DESC, null, null);
        stage.instructions.add(TailRegionExtractor.stagePrologue("mobServerAiStepSuccessor"));
        for (AbstractInsnNode node1 : tail) {
            stage.instructions.add(node1.clone(null));
        }
        stage.instructions.add(new InsnNode(Opcodes.RETURN));
        stage.maxStack = Math.max(6, host.maxStack);
        stage.maxLocals = 2;
        node.methods.add(stage);

        for (int index = anchor + 1; index < instructions.length; index++) {
            host.instructions.remove(instructions[index]);
        }
        InsnList call = new InsnList();
        call.add(new VarInsnNode(Opcodes.ALOAD, 0));
        call.add(new VarInsnNode(Opcodes.ALOAD, 1));
        call.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, OWNER, STAGE, STAGE_DESC, false));
        call.add(new InsnNode(Opcodes.RETURN));
        host.instructions.add(call);
        return true;
    }
}
