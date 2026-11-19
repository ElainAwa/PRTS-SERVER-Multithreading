package io.izzel.arclight.boot.asm;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lifts the prefix of {@code Mob.serverAiStep} in front of the navigation field load into its own
 * stage that returns the profiler it opened, so prefix, span and successor of the navigation section
 * are all callable stages. The prefix owns its locals (this and the profiler it stores) and carries
 * no exception table; the host keeps the navigation field load and its call at the same position.
 */
public final class PrtsMobServerAiStepPrefix implements Implementer {

    private static final String OWNER = "net/minecraft/world/entity/Mob";
    private static final String HOST = "serverAiStep";
    private static final String HOST_DESC = "()V";
    private static final String NAV = "net/minecraft/world/entity/ai/navigation/PathNavigation";
    private static final String STAGE = "prts$serverAiStepPrefix";
    private static final String STAGE_DESC = "()Lnet/minecraft/util/profiling/ProfilerFiller;";

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
            if (instructions[index] instanceof MethodInsnNode call
                && PrtsStageAnchors.isNavigationCall(call)) {
                anchor = index;
                break;
            }
        }
        if (anchor < 2) {
            return false;
        }
        // The call region is the contiguous run of receiver/argument loads in front of the call;
        // it may be the plain field load or the redirect handler's extra receiver slot. The prefix is
        // everything before that run.
        int callStart = anchor;
        boolean navLoad = false;
        while (callStart > 0) {
            AbstractInsnNode previous = instructions[callStart - 1];
            if (previous instanceof VarInsnNode || previous instanceof FieldInsnNode) {
                if (previous instanceof FieldInsnNode field
                    && ("L" + NAV + ";").equals(field.desc)) {
                    navLoad = true;
                }
                callStart--;
                continue;
            }
            break;
        }
        if (!navLoad) {
            if (Boolean.getBoolean("arclight.prts.stageTrace")) {
                StringBuilder shape = new StringBuilder();
                for (int index = Math.max(0, anchor - 5); index <= anchor
                    && index < instructions.length; index++) {
                    AbstractInsnNode node = instructions[index];
                    shape.append(index).append(':').append(node.getOpcode()).append(':');
                    if (node instanceof FieldInsnNode field) {
                        shape.append("F ").append(field.owner).append('.').append(field.name)
                            .append(field.desc).append(' ');
                    } else if (node instanceof MethodInsnNode call) {
                        shape.append("M ").append(call.owner).append('.').append(call.name)
                            .append(call.desc).append(' ');
                    } else if (node instanceof VarInsnNode variable) {
                        shape.append("V ").append(variable.var).append(' ');
                    }
                }
                System.out.println("[PRTS-ANCHOR2] " + shape);
            }
            return false;
        }
        List<AbstractInsnNode> region = new ArrayList<>();
        for (int index = 0; index < callStart; index++) {
            AbstractInsnNode node1 = instructions[index];
            if (node1 instanceof LineNumberNode) {
                continue;
            }
            if (node1 instanceof TableSwitchInsnNode || node1 instanceof LookupSwitchInsnNode
                || node1 instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.GOTO
                    && anchor < 0) {
                return false;
            }
            if (node1 instanceof InsnNode insn && insn.getOpcode() >= Opcodes.IRETURN
                && insn.getOpcode() <= Opcodes.RETURN) {
                return false;
            }
            region.add(node1);
        }
        if (region.isEmpty()) {
            return false;
        }
        Map<LabelNode, LabelNode> labels = new HashMap<>();
        for (AbstractInsnNode node1 : region) {
            if (node1 instanceof LabelNode label) {
                labels.put(label, new LabelNode());
            }
        }
        for (AbstractInsnNode node1 : region) {
            if (node1 instanceof JumpInsnNode jump && !labels.containsKey(jump.label)) {
                return false;
            }
        }
        MethodNode stage = new MethodNode(Opcodes.ACC_PRIVATE, STAGE, STAGE_DESC, null, null);
        stage.instructions.add(TailRegionExtractor.stagePrologue("mobServerAiStepPrefix"));
        for (AbstractInsnNode node1 : region) {
            stage.instructions.add(node1.clone(labels));
        }
        stage.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        stage.instructions.add(new InsnNode(Opcodes.ARETURN));
        stage.maxStack = Math.max(6, host.maxStack);
        stage.maxLocals = Math.max(2, host.maxLocals);
        node.methods.add(stage);

        for (int index = 0; index < callStart; index++) {
            host.instructions.remove(instructions[index]);
        }
        InsnList call = new InsnList();
        call.add(new VarInsnNode(Opcodes.ALOAD, 0));
        call.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, OWNER, STAGE, STAGE_DESC, false));
        call.add(new VarInsnNode(Opcodes.ASTORE, 1));
        host.instructions.insert(call);
        if (host.localVariables != null) {
            List<org.objectweb.asm.tree.LocalVariableNode> kept = new ArrayList<>();
            for (org.objectweb.asm.tree.LocalVariableNode variable : host.localVariables) {
                if (host.instructions.contains(variable.start)
                    && host.instructions.contains(variable.end)) {
                    kept.add(variable);
                }
            }
            host.localVariables.clear();
            host.localVariables.addAll(kept);
        }
        host.localVariables = null;
        host.visibleLocalVariableAnnotations = null;
        host.invisibleLocalVariableAnnotations = null;
        return true;
    }
}