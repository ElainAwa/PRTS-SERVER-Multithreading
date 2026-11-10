package io.izzel.arclight.common.mod.mixins;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.HashMap;
import java.util.Map;

/** Runtime ABI bridges for mods compiled against older Create versions. Every bridge is applied in
 * preApply, before the third-party mixin scans that class. */
public final class RailwaysInjectors {

    public static final String ABSTRACT_BOGEY = "com.simibubi.create.content.trains.bogey.AbstractBogeyBlock";
    public static final String TRACK_BLOCK = "com.simibubi.create.content.trains.track.TrackBlock";
    public static final String GLOBAL_STATION = "com.simibubi.create.content.trains.station.GlobalStation";
    public static final String CARRIAGE_CONTRAPTION_ENTITY =
        "com.simibubi.create.content.trains.entity.CarriageContraptionEntity";
    public static final String STATION_BLOCK = "com.simibubi.create.content.trains.station.StationBlock";
    private static final String BHR = "Lnet/minecraft/world/phys/BlockHitResult;";
    private static final String OLD_USE_DESC =
        "(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;"
            + "Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/player/Player;"
            + "Lnet/minecraft/world/InteractionHand;" + BHR + ")Lnet/minecraft/world/InteractionResult;";
    private static final String NEW_USE_DESC =
        "(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/block/state/BlockState;"
            + "Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;"
            + BHR + ")Lnet/minecraft/world/ItemInteractionResult;";
    private static final String OLD_READ_DESC =
        "(Lnet/minecraft/nbt/CompoundTag;ZLcom/simibubi/create/content/trains/graph/DimensionPalette;)V";
    private static final String NEW_READ_DESC =
        "(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;"
            + "ZLcom/simibubi/create/content/trains/graph/DimensionPalette;)V";
    private static final String OLD_WRITE_DESC =
        "(Lnet/minecraft/nbt/CompoundTag;Lcom/simibubi/create/content/trains/graph/DimensionPalette;)V";
    private static final String NEW_WRITE_DESC =
        "(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;"
            + "Lcom/simibubi/create/content/trains/graph/DimensionPalette;)V";
    private static final String USE_WITHOUT_ITEM_DESC =
        "(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;"
            + "Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/player/Player;"
            + "Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/InteractionResult;";

    private RailwaysInjectors() {
    }

    public static boolean injectAbstractBogey(ClassNode node) {
        if (node == null) {
            return false;
        }
        MethodNode useItemOn = method(node, "useItemOn", NEW_USE_DESC);
        if (useItemOn == null || method(node, "use", OLD_USE_DESC) != null) {
            return useItemOn != null;
        }
        MethodNode legacy = cloneAsLegacyUse(node, useItemOn);
        node.methods.add(legacy);
        replaceWithItemAdapter(node, useItemOn);
        return true;
    }

    public static boolean injectTrackBlock(ClassNode node) {
        if (node == null) {
            return false;
        }
        boolean added = false;
        if (method(node, "use", OLD_USE_DESC) == null) {
            node.methods.add(constantReturn("use", OLD_USE_DESC));
            added = true;
        }
        if (method(node, "extendedUse", OLD_USE_DESC) == null) {
            node.methods.add(constantReturn("extendedUse", OLD_USE_DESC));
            added = true;
        }
        if (method(node, "useWithoutItem", USE_WITHOUT_ITEM_DESC) == null) {
            // Real empty-hand entry: the game dispatches here only when useItemOn did not consume
            // the interaction; returning PASS mirrors the inherited default and lets the stale
            // Railways HEAD injection run without duplicating Create's item path.
            MethodNode emptyHand = new MethodNode(Opcodes.ACC_PROTECTED, "useWithoutItem",
                USE_WITHOUT_ITEM_DESC, null, null);
            emptyHand.instructions.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.GETSTATIC,
                "net/minecraft/world/InteractionResult", "PASS",
                "Lnet/minecraft/world/InteractionResult;"));
            emptyHand.instructions.add(new InsnNode(Opcodes.ARETURN));
            emptyHand.maxLocals = 6;
            emptyHand.maxStack = 1;
            node.methods.add(emptyHand);
            added = true;
        }
        return added;
    }

    public static boolean injectGlobalStation(ClassNode node) {
        if (node == null) {
            return false;
        }
        MethodNode newRead = method(node, "read", NEW_READ_DESC);
        if (newRead == null) {
            return false;
        }
        if (method(node, "read", OLD_READ_DESC) == null) {
            MethodNode legacy = new MethodNode(Opcodes.ACC_PUBLIC, "read", OLD_READ_DESC, null, null);
            legacy.instructions.add(new InsnNode(Opcodes.RETURN));
            legacy.maxLocals = 5;
            legacy.maxStack = 0;
            node.methods.add(legacy);
        }
        int inserted = 0;
        for (AbstractInsnNode instruction : newRead.instructions) {
            if (instruction.getOpcode() == Opcodes.RETURN && !alreadyCalls(instruction, "read", OLD_READ_DESC)) {
                InsnList call = new InsnList();
                call.add(new VarInsnNode(Opcodes.ALOAD, 0));
                call.add(new VarInsnNode(Opcodes.ALOAD, 1));
                call.add(new VarInsnNode(Opcodes.ILOAD, 3));
                call.add(new VarInsnNode(Opcodes.ALOAD, 4));
                call.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, "read", OLD_READ_DESC,
                    false));
                newRead.instructions.insertBefore(instruction, call);
                inserted++;
            }
        }
        MethodNode newWrite = method(node, "write", NEW_WRITE_DESC);
        if (newWrite != null) {
            if (method(node, "write", OLD_WRITE_DESC) == null) {
                MethodNode legacyWrite = new MethodNode(Opcodes.ACC_PUBLIC, "write", OLD_WRITE_DESC,
                    null, null);
                legacyWrite.instructions.add(new InsnNode(Opcodes.RETURN));
                legacyWrite.maxLocals = 3;
                legacyWrite.maxStack = 0;
                node.methods.add(legacyWrite);
            }
            for (AbstractInsnNode instruction : newWrite.instructions) {
                if (instruction.getOpcode() == Opcodes.RETURN
                    && !alreadyCalls(instruction, "write", OLD_WRITE_DESC)) {
                    InsnList call = new InsnList();
                    call.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    call.add(new VarInsnNode(Opcodes.ALOAD, 1));
                    call.add(new VarInsnNode(Opcodes.ALOAD, 3));
                    call.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, "write",
                        OLD_WRITE_DESC, false));
                    newWrite.instructions.insertBefore(instruction, call);
                    inserted++;
                }
            }
        }
        return inserted > 0;
    }

    /** The old control path checked the player interaction distance inline with
     * {@code Vec3.closerThan(Position, double)}; Create now delegates that check to
     * {@code canInteractWithBlock}. A real closerThan call is added as a marker: it is false for a
     * normal player, so the original helper still decides, while Railways' wrapper returns true for
     * its fake conductor and the helper check is skipped for that one call. */
    public static boolean injectCarriageContraptionEntity(ClassNode node) {
        if (node == null) {
            return false;
        }
        String desc =
            "(Lnet/minecraft/core/BlockPos;Ljava/util/Collection;Lnet/minecraft/world/entity/player/Player;)Z";
        MethodNode control = method(node, "control", desc);
        if (control == null) {
            return false;
        }
        for (AbstractInsnNode instruction : control.instructions) {
            if (isCloserThan(instruction)) {
                return false;
            }
        }
        for (AbstractInsnNode instruction : control.instructions) {
            if (!(instruction instanceof MethodInsnNode call) || !call.name.equals("canInteractWithBlock")
                || !call.desc.equals("(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/phys/Vec3;D)Z")) {
                continue;
            }
            AbstractInsnNode start = call.getPrevious();
            for (int back = 0; back < 4 && start != null; back++) {
                start = start.getPrevious();
            }
            if (start == null || !(call.getNext() instanceof org.objectweb.asm.tree.JumpInsnNode jump)) {
                return false;
            }
            LabelNode local = new LabelNode();
            InsnList marker = new InsnList();
            marker.add(new VarInsnNode(Opcodes.ALOAD, 3));
            marker.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "net/minecraft/world/entity/player/Player", "position",
                "()Lnet/minecraft/world/phys/Vec3;", false));
            marker.add(new org.objectweb.asm.tree.TypeInsnNode(Opcodes.NEW, "net/minecraft/world/phys/Vec3"));
            marker.add(new InsnNode(Opcodes.DUP));
            marker.add(new org.objectweb.asm.tree.LdcInsnNode(1.0E30D));
            marker.add(new org.objectweb.asm.tree.LdcInsnNode(1.0E30D));
            marker.add(new org.objectweb.asm.tree.LdcInsnNode(1.0E30D));
            marker.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "net/minecraft/world/phys/Vec3",
                "<init>", "(DDD)V", false));
            marker.add(new org.objectweb.asm.tree.LdcInsnNode(0.0D));
            marker.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "net/minecraft/world/phys/Vec3",
                "closerThan", "(Lnet/minecraft/core/Position;D)Z", false));
            marker.add(new org.objectweb.asm.tree.JumpInsnNode(Opcodes.IFEQ, local));
            marker.add(new org.objectweb.asm.tree.JumpInsnNode(Opcodes.GOTO, jump.label));
            marker.add(local);
            control.instructions.insertBefore(start, marker);
            return true;
        }
        return false;
    }

    /** StationBlock used to dispatch through the vanilla {@code use} entry; Create 6.0.10 only
     * overrides {@code useItemOn}, while Railways still injects autoWhistle into {@code use}. The
     * real useItemOn entry first calls a legacy {@code use} host (return value decides whether the
     * vanilla body still runs) and otherwise delegates to the renamed original body once. The new
     * Railways useItemOn HEAD injection still lands on the real entry. */
    public static boolean injectStationBlock(ClassNode node) {
        if (node == null) {
            return false;
        }
        MethodNode useItemOn = method(node, "useItemOn", NEW_USE_DESC);
        if (useItemOn == null || method(node, "arclight$stationUseItemOnBody", NEW_USE_DESC) != null) {
            return useItemOn != null;
        }
        if (method(node, "use", OLD_USE_DESC) == null) {
            MethodNode host = new MethodNode(Opcodes.ACC_PUBLIC, "use", OLD_USE_DESC, null, null);
            host.instructions.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.GETSTATIC,
                "net/minecraft/world/InteractionResult", "PASS",
                "Lnet/minecraft/world/InteractionResult;"));
            host.instructions.add(new InsnNode(Opcodes.ARETURN));
            host.maxLocals = 7;
            host.maxStack = 1;
            node.methods.add(host);
        }
        int access = useItemOn.access;
        useItemOn.name = "arclight$stationUseItemOnBody";
        MethodNode adapter = new MethodNode(access, "useItemOn", NEW_USE_DESC, null, null);
        LabelNode normal = new LabelNode();
        InsnList list = new InsnList();
        list.add(new VarInsnNode(Opcodes.ALOAD, 0));
        list.add(new VarInsnNode(Opcodes.ALOAD, 2));
        list.add(new VarInsnNode(Opcodes.ALOAD, 3));
        list.add(new VarInsnNode(Opcodes.ALOAD, 4));
        list.add(new VarInsnNode(Opcodes.ALOAD, 5));
        list.add(new VarInsnNode(Opcodes.ALOAD, 6));
        list.add(new VarInsnNode(Opcodes.ALOAD, 7));
        list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, "use", OLD_USE_DESC, false));
        list.add(new VarInsnNode(Opcodes.ASTORE, 8));
        list.add(new VarInsnNode(Opcodes.ALOAD, 8));
        list.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.GETSTATIC,
            "net/minecraft/world/InteractionResult", "PASS",
            "Lnet/minecraft/world/InteractionResult;"));
        list.add(new org.objectweb.asm.tree.JumpInsnNode(Opcodes.IF_ACMPEQ, normal));
        list.add(new VarInsnNode(Opcodes.ALOAD, 8));
        list.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
            "io/izzel/arclight/common/mod/mixins/RailwaysCompat", "toItemInteractionResult",
            "(Lnet/minecraft/world/InteractionResult;)Lnet/minecraft/world/ItemInteractionResult;", false));
        list.add(new InsnNode(Opcodes.ARETURN));
        list.add(normal);
        list.add(new VarInsnNode(Opcodes.ALOAD, 0));
        list.add(new VarInsnNode(Opcodes.ALOAD, 1));
        list.add(new VarInsnNode(Opcodes.ALOAD, 2));
        list.add(new VarInsnNode(Opcodes.ALOAD, 3));
        list.add(new VarInsnNode(Opcodes.ALOAD, 4));
        list.add(new VarInsnNode(Opcodes.ALOAD, 5));
        list.add(new VarInsnNode(Opcodes.ALOAD, 6));
        list.add(new VarInsnNode(Opcodes.ALOAD, 7));
        list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name,
            "arclight$stationUseItemOnBody", NEW_USE_DESC, false));
        list.add(new InsnNode(Opcodes.ARETURN));
        adapter.instructions = list;
        adapter.maxLocals = 9;
        adapter.maxStack = 8;
        adapter.localVariables = null;
        adapter.visibleLocalVariableAnnotations = null;
        adapter.invisibleLocalVariableAnnotations = null;
        adapter.visibleTypeAnnotations = null;
        adapter.invisibleTypeAnnotations = null;
        node.methods.add(adapter);
        return true;
    }

    private static boolean isCloserThan(AbstractInsnNode instruction) {
        return instruction instanceof MethodInsnNode call && call.owner.equals("net/minecraft/world/phys/Vec3")
            && call.name.equals("closerThan") && call.desc.equals("(Lnet/minecraft/core/Position;D)Z");
    }

    private static boolean alreadyCalls(AbstractInsnNode returnInstruction, String name, String desc) {
        AbstractInsnNode cursor = returnInstruction.getPrevious();
        while (cursor != null && cursor.getOpcode() == -1) {
            cursor = cursor.getPrevious();
        }
        return cursor instanceof MethodInsnNode call && call.name.equals(name) && call.desc.equals(desc);
    }

    private static MethodNode constantReturn(String name, String desc) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, name, desc, null, null);
        method.instructions.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.GETSTATIC,
            "net/minecraft/world/InteractionResult", "PASS",
            "Lnet/minecraft/world/InteractionResult;"));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        method.maxLocals = 7;
        method.maxStack = 1;
        return method;
    }

    private static MethodNode cloneAsLegacyUse(ClassNode node, MethodNode original) {
        MethodNode legacy = new MethodNode(Opcodes.ACC_PUBLIC, "use", OLD_USE_DESC, null, null);
        Map<Integer, Integer> remap = new HashMap<>();
        remap.put(1, 7);
        remap.put(2, 1);
        remap.put(3, 2);
        remap.put(4, 3);
        remap.put(5, 4);
        remap.put(6, 5);
        remap.put(7, 6);
        Map<LabelNode, LabelNode> labels = new HashMap<>();
        for (AbstractInsnNode instruction : original.instructions) {
            if (instruction instanceof LabelNode label) {
                labels.put(label, new LabelNode());
            }
        }
        InsnList list = new InsnList();
        list.add(new VarInsnNode(Opcodes.ALOAD, 4));
        list.add(new VarInsnNode(Opcodes.ALOAD, 5));
        list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
            "net/minecraft/world/entity/player/Player", "getItemInHand",
            "(Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/item/ItemStack;", false));
        list.add(new VarInsnNode(Opcodes.ASTORE, 7));
        for (AbstractInsnNode instruction : original.instructions) {
            if (instruction instanceof FrameNode || instruction instanceof LineNumberNode) {
                continue;
            }
            AbstractInsnNode copy = instruction.clone(labels);
            if (copy instanceof VarInsnNode var) {
                var.var = remap.getOrDefault(var.var, var.var);
            }
            if (copy.getOpcode() == Opcodes.ARETURN) {
                list.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "io/izzel/arclight/common/mod/mixins/RailwaysCompat", "toInteractionResult",
                    "(Lnet/minecraft/world/ItemInteractionResult;)Lnet/minecraft/world/InteractionResult;",
                    false));
            }
            list.add(copy);
        }
        legacy.instructions = list;
        legacy.maxLocals = original.maxLocals;
        legacy.maxStack = original.maxStack + 2;
        // The copied body no longer matches the original local variable table; keeping it would
        // produce duplicated/stale LocalVariableTable entries. Drop debug tables and type
        // annotations and let the frame writer recompute the stack map for the new method.
        legacy.localVariables = null;
        legacy.visibleLocalVariableAnnotations = null;
        legacy.invisibleLocalVariableAnnotations = null;
        legacy.tryCatchBlocks.clear();
        legacy.visibleTypeAnnotations = null;
        legacy.invisibleTypeAnnotations = null;
        return legacy;
    }

    private static void replaceWithItemAdapter(ClassNode node, MethodNode useItemOn) {
        InsnList list = new InsnList();
        list.add(new VarInsnNode(Opcodes.ALOAD, 0));
        list.add(new VarInsnNode(Opcodes.ALOAD, 2));
        list.add(new VarInsnNode(Opcodes.ALOAD, 3));
        list.add(new VarInsnNode(Opcodes.ALOAD, 4));
        list.add(new VarInsnNode(Opcodes.ALOAD, 5));
        list.add(new VarInsnNode(Opcodes.ALOAD, 6));
        list.add(new VarInsnNode(Opcodes.ALOAD, 7));
        list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, "use", OLD_USE_DESC, false));
        list.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
            "io/izzel/arclight/common/mod/mixins/RailwaysCompat", "toItemInteractionResult",
            "(Lnet/minecraft/world/InteractionResult;)Lnet/minecraft/world/ItemInteractionResult;",
            false));
        list.add(new InsnNode(Opcodes.ARETURN));
        useItemOn.instructions = list;
        useItemOn.maxLocals = 8;
        useItemOn.maxStack = 8;
        // The old body (and its debug/type tables) is gone; stale LocalVariableTable entries were
        // what produced the duplicated bogeyBlockEntity attribute at class load.
        useItemOn.localVariables = null;
        useItemOn.visibleLocalVariableAnnotations = null;
        useItemOn.invisibleLocalVariableAnnotations = null;
        useItemOn.tryCatchBlocks.clear();
        useItemOn.visibleTypeAnnotations = null;
        useItemOn.invisibleTypeAnnotations = null;
    }

    private static MethodNode method(ClassNode node, String name, String desc) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) {
                return method;
            }
        }
        return null;
    }
}
