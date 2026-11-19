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
 * Moves the straight-line-or-branching region that follows one anchored call in a host method into
 * its own private instance method and makes the host call it at the same position. The region owns
 * its locals, carries its labels and frames with it and has no exception table in the shapes this
 * transform accepts; a shape it cannot move is refused instead of approximated.
 */
public final class TailRegionExtractor {

    /** The anchored call the extracted region starts after. */
    public interface Anchor {
        boolean matches(MethodInsnNode call);
    }

    private TailRegionExtractor() {
    }

    /** @return whether the host was rewritten; false keeps the class untouched. */
    public static boolean extract(ClassNode node, String hostName, String hostDesc, Anchor anchor,
                                  String stageName) {
        MethodNode host = null;
        for (MethodNode method : node.methods) {
            if (stageName.equals(method.name)) {
                return false;
            }
            if (hostName.equals(method.name) && hostDesc.equals(method.desc)) {
                host = method;
            }
        }
        if (host == null) {
            return false;
        }
        AbstractInsnNode[] instructions = host.instructions.toArray();
        int anchorIndex = -1;
        for (int index = 0; index < instructions.length; index++) {
            if (instructions[index] instanceof MethodInsnNode call && anchor.matches(call)) {
                anchorIndex = index;
                break;
            }
        }
        if (anchorIndex < 0) {
            return false;
        }
        List<AbstractInsnNode> region = new ArrayList<>();
        boolean terminated = false;
        for (int index = anchorIndex + 1; index < instructions.length; index++) {
            AbstractInsnNode node1 = instructions[index];
            if (node1 instanceof LineNumberNode) {
                continue;
            }
            if (node1 instanceof FrameNode) {
                region.add(node1);
                continue;
            }
            if (node1 instanceof TableSwitchInsnNode || node1 instanceof LookupSwitchInsnNode) {
                return false;
            }
            if (node1.getOpcode() == Opcodes.RETURN) {
                terminated = true;
                break;
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
        // A jump before the anchor that lands inside the moved region would lose its target in the
        // host frame. A jump to the first label of the region only skips the anchored call itself,
        // so it can be retargeted to the stage call; any deeper entry is refused instead of being
        // split into two entries by hand.
        LabelNode regionStart = null;
        for (AbstractInsnNode node1 : region) {
            if (node1 instanceof LabelNode label) {
                regionStart = label;
                break;
            }
        }
        List<JumpInsnNode> retarget = new ArrayList<>();
        for (int index = 0; index < anchorIndex; index++) {
            if (instructions[index] instanceof JumpInsnNode jump && labels.containsKey(jump.label)) {
                if (jump.label != regionStart) {
                    return false;
                }
                retarget.add(jump);
            }
        }
        for (AbstractInsnNode node1 : region) {
            if (node1 instanceof JumpInsnNode jump && !labels.containsKey(jump.label)) {
                return false;
            }
        }
        MethodNode stage = new MethodNode(Opcodes.ACC_PRIVATE, stageName, "()V", null, null);
        for (AbstractInsnNode node1 : region) {
            stage.instructions.add(node1.clone(labels));
        }
        stage.instructions.add(new InsnNode(Opcodes.RETURN));
        stage.maxStack = Math.max(6, host.maxStack);
        stage.maxLocals = Math.max(1, host.maxLocals);
        node.methods.add(stage);

        for (int index = anchorIndex + 1; index < instructions.length; index++) {
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
        LabelNode stageEntry = new LabelNode();
        call.add(stageEntry);
        for (JumpInsnNode jump : retarget) {
            jump.label = stageEntry;
        }
        call.add(new VarInsnNode(Opcodes.ALOAD, 0));
        call.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, stageName, "()V", false));
        call.add(new InsnNode(Opcodes.RETURN));
        host.instructions.add(call);
        return true;
    }
}
