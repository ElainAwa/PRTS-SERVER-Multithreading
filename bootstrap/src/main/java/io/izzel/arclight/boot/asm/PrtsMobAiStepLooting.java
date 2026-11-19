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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Extracts the looting successor block of {@code Mob.aiStep} after the super call into one callable
 * stage method. The block owns its own locals (pickup reach, iterator, item), so it can be moved into
 * an instance method with the same slots; branch targets and frames move with it, and the host keeps
 * its line before this one and calls the stage exactly once.
 */
public final class PrtsMobAiStepLooting implements Implementer {

    private static final String OWNER = "net/minecraft/world/entity/Mob";
    private static final String HOST = "aiStep";
    private static final String HOST_DESC = "()V";
    private static final String SUPER_OWNER = "net/minecraft/world/entity/LivingEntity";
    private static final String STAGE = "prts$aiStepLooting";
    private static final String STAGE_DESC = "()V";

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
                && call.getOpcode() == Opcodes.INVOKESPECIAL && SUPER_OWNER.equals(call.owner)
                && "aiStep".equals(call.name) && "()V".equals(call.desc)) {
                anchor = index;
                break;
            }
        }
        if (anchor < 0) {
            return false;
        }
        List<AbstractInsnNode> region = new ArrayList<>();
        boolean terminated = false;
        for (int index = anchor + 1; index < instructions.length; index++) {
            AbstractInsnNode node1 = instructions[index];
            if (node1 instanceof FrameNode) {
                region.add(node1);
                continue;
            }
            if (node1 instanceof LineNumberNode) {
                continue;
            }
            if (node1 instanceof TableSwitchInsnNode || node1 instanceof LookupSwitchInsnNode) {
                return false;
            }
            if (node1.getOpcode() == Opcodes.RETURN) {
                terminated = true;
                break;
            }
            if (node1 instanceof VarInsnNode variable && variable.var > 8) {
                return false;
            }
            region.add(node1);
        }
        if (!terminated || region.isEmpty()) {
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
        stage.instructions.add(TailRegionExtractor.stagePrologue("mobAiStepLooting"));
        for (AbstractInsnNode node1 : region) {
            stage.instructions.add(node1.clone(labels));
        }
        stage.instructions.add(new InsnNode(Opcodes.RETURN));
        stage.maxStack = Math.max(6, host.maxStack);
        stage.maxLocals = Math.max(4, host.maxLocals);
        node.methods.add(stage);

        for (int index = anchor + 1; index < instructions.length; index++) {
            host.instructions.remove(instructions[index]);
        }
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
        InsnList call = new InsnList();
        call.add(new VarInsnNode(Opcodes.ALOAD, 0));
        call.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, OWNER, STAGE, STAGE_DESC, false));
        call.add(new InsnNode(Opcodes.RETURN));
        host.instructions.add(call);
        return true;
    }
}
