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

    /** Emits one stage-entry counter call to the observation face. */
    public static InsnList stagePrologue(String stage) {
        InsnList list = new InsnList();
        list.add(new org.objectweb.asm.tree.LdcInsnNode(stage));
        list.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
            "io/izzel/arclight/common/prts/kernel/domain/entity/owner/StageCounters",
            "note", "(Ljava/lang/String;)V", false));
        return list;
    }

    private static AbstractInsnNode pushInt(int value) {
        if (value >= -1 && value <= 5) {
            return new InsnNode(Opcodes.ICONST_0 + value);
        }
        if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            return new org.objectweb.asm.tree.IntInsnNode(Opcodes.BIPUSH, value);
        }
        return new org.objectweb.asm.tree.IntInsnNode(Opcodes.SIPUSH, value);
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
        // A jump before the anchor that lands inside the moved region keeps its skip semantics only
        // through a stage entry of its own: the region start is entry 0, every deeper target gets the
        // next entry and the host branch becomes a trampoline call with that entry index.
        LabelNode regionStart = null;
        for (AbstractInsnNode node1 : region) {
            if (node1 instanceof LabelNode label) {
                regionStart = label;
                break;
            }
        }
        List<LabelNode> entries = new ArrayList<>();
        if (regionStart != null) {
            entries.add(regionStart);
        }
        Map<LabelNode, Integer> entryIndex = new HashMap<>();
        if (regionStart != null) {
            entryIndex.put(regionStart, 0);
        }
        for (int index = 0; index < anchorIndex; index++) {
            if (instructions[index] instanceof JumpInsnNode jump && labels.containsKey(jump.label)
                && !entryIndex.containsKey(jump.label)) {
                entryIndex.put(jump.label, entries.size());
                entries.add(jump.label);
            }
        }
        boolean multiEntry = entries.size() > 1;
        if (regionStart == null && !entries.isEmpty()) {
            return false;
        }
        for (AbstractInsnNode node1 : region) {
            if (node1 instanceof JumpInsnNode jump && !labels.containsKey(jump.label)) {
                return false;
            }
        }
        String stageDesc = multiEntry ? "(I)V" : "()V";
        MethodNode stage = new MethodNode(Opcodes.ACC_PRIVATE, stageName, stageDesc, null, null);
        stage.instructions.add(stagePrologue(node.name + "#" + stageName + stageDesc));
        if (multiEntry) {
            LabelNode[] targets = new LabelNode[entries.size()];
            for (int index = 0; index < entries.size(); index++) {
                targets[index] = labels.get(entries.get(index));
            }
            if (targets[0] == null) {
                return false;
            }
            stage.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
            stage.instructions.add(new TableSwitchInsnNode(0, entries.size() - 1,
                targets[0], targets));
        }
        for (AbstractInsnNode node1 : region) {
            stage.instructions.add(node1.clone(labels));
        }
        stage.instructions.add(new InsnNode(Opcodes.RETURN));
        stage.maxStack = Math.max(6, host.maxStack + 1);
        stage.maxLocals = Math.max(multiEntry ? 2 : 1, host.maxLocals);
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
        LabelNode normalEntry = new LabelNode();
        List<LabelNode> trampolines = new ArrayList<>();
        if (multiEntry) {
            for (int index = 1; index < entries.size(); index++) {
                trampolines.add(new LabelNode());
            }
        }
        for (int index = 0; index < anchorIndex; index++) {
            if (instructions[index] instanceof JumpInsnNode jump) {
                Integer entry = entryIndex.get(jump.label);
                if (entry == null) {
                    continue;
                }
                jump.label = entry == 0 ? normalEntry : trampolines.get(entry - 1);
            }
        }
        call.add(normalEntry);
        call.add(new VarInsnNode(Opcodes.ALOAD, 0));
        if (multiEntry) {
            call.add(new InsnNode(Opcodes.ICONST_0));
            call.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, stageName, stageDesc, false));
        } else {
            call.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, stageName, stageDesc, false));
        }
        call.add(new InsnNode(Opcodes.RETURN));
        for (int index = 0; index < trampolines.size(); index++) {
            call.add(trampolines.get(index));
            call.add(new VarInsnNode(Opcodes.ALOAD, 0));
            call.add(pushInt(index + 1));
            call.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, stageName, stageDesc, false));
            call.add(new InsnNode(Opcodes.RETURN));
        }
        host.instructions.add(call);
        host.localVariables = null;
        host.visibleLocalVariableAnnotations = null;
        host.invisibleLocalVariableAnnotations = null;
        return true;
    }
}