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

    private static void addInterface(ClassNode node) {
        String name = "io/izzel/arclight/common/prts/kernel/domain/entity/owner/NavigationSectionStages";
        if (!node.interfaces.contains(name)) {
            node.interfaces.add(name);
        }
    }

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
        // it may be the plain field load or the redirect handler's extra receiver slot.
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
            return false;
        }
        // A cancellable HEAD keeps its early exit in the host; the lifted prefix starts at the first
        // label after the host-side exit paths, so the stage only has a normal empty-stack return.
        int regionStart = -1;
        for (int candidate = 0; candidate < callStart; candidate++) {
            boolean isEntry = candidate == 0 || instructions[candidate] instanceof LabelNode;
            if (!isEntry) {
                continue;
            }
            boolean regionClosed = true;
            for (int index = candidate; index < callStart; index++) {
                AbstractInsnNode node1 = instructions[index];
                if (node1.getOpcode() >= Opcodes.IRETURN && node1.getOpcode() <= Opcodes.RETURN) {
                    regionClosed = false;
                    break;
                }
            }
            if (!regionClosed) {
                continue;
            }
            boolean hostClosed = true;
            for (int index = 0; index < candidate && hostClosed; index++) {
                if (instructions[index] instanceof JumpInsnNode jump) {
                    for (int target = candidate; target < callStart; target++) {
                        if (instructions[target] == jump.label && target != candidate) {
                            hostClosed = false;
                            break;
                        }
                    }
                }
            }
            if (!hostClosed) {
                continue;
            }
            regionStart = candidate;
            break;
        }
        if (regionStart < 0) {
            return false;
        }
        List<AbstractInsnNode> region = new ArrayList<>();
        for (int index = regionStart; index < callStart; index++) {
            AbstractInsnNode node1 = instructions[index];
            if (node1 instanceof LineNumberNode) {
                continue;
            }
            if (node1 instanceof TableSwitchInsnNode || node1 instanceof LookupSwitchInsnNode) {
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
        LabelNode regionEntry = null;
        for (AbstractInsnNode node1 : region) {
            if (node1 instanceof LabelNode label) {
                regionEntry = label;
                break;
            }
        }
        LabelNode callStartLabel = null;
        for (int index = callStart; index < instructions.length; index++) {
            if (instructions[index] instanceof LabelNode label) {
                callStartLabel = label;
                break;
            }
            if (!(instructions[index] instanceof LineNumberNode)) {
                break;
            }
        }
        List<JumpInsnNode> epilogueJumps = new ArrayList<>();
        for (AbstractInsnNode node1 : region) {
            if (node1 instanceof JumpInsnNode jump) {
                if (labels.containsKey(jump.label)) {
                    continue;
                }
                if (jump.label != callStartLabel) {
                    return false;
                }
                epilogueJumps.add(jump);
            }
        }
        MethodNode stage = new MethodNode(Opcodes.ACC_PUBLIC, STAGE, STAGE_DESC, null, null);
        stage.instructions.add(TailRegionExtractor.stagePrologue(OWNER + "#" + "mobServerAiStepPrefix"));
        for (AbstractInsnNode node1 : region) {
            stage.instructions.add(node1.clone(labels));
        }
        LabelNode epilogue = new LabelNode();
        for (JumpInsnNode jump : epilogueJumps) {
            jump.label = epilogue;
        }
        stage.instructions.add(epilogue);
        stage.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        stage.instructions.add(new InsnNode(Opcodes.ARETURN));
        stage.maxStack = Math.max(6, host.maxStack);
        stage.maxLocals = Math.max(2, host.maxLocals);
        node.methods.add(stage);
        addInterface(node);

        LabelNode stageEntry = new LabelNode();
        for (int index = 0; index < regionStart; index++) {
            if (instructions[index] instanceof JumpInsnNode jump && jump.label == regionEntry) {
                jump.label = stageEntry;
            }
        }
        for (int index = regionStart; index < callStart; index++) {
            host.instructions.remove(instructions[index]);
        }
        InsnList call = new InsnList();
        call.add(stageEntry);
        call.add(new VarInsnNode(Opcodes.ALOAD, 0));
        call.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, OWNER, STAGE, STAGE_DESC, false));
        call.add(new VarInsnNode(Opcodes.ASTORE, 1));
        host.instructions.insertBefore(instructions[callStart], call);
        host.localVariables = null;
        host.visibleLocalVariableAnnotations = null;
        host.invisibleLocalVariableAnnotations = null;
        return true;
    }
}