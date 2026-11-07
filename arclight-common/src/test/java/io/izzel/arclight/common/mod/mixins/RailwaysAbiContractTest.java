package io.izzel.arclight.common.mod.mixins;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Offline contract for the three Railways ABI bridges: the new entry points must be the real
 * paths, the legacy entries must be reachable for the stale injections, and repeated preApply must
 * stay idempotent. */
public class RailwaysAbiContractTest {

    private static final String OLD_USE = "(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/InteractionResult;";
    private static final String NEW_USE = "(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/ItemInteractionResult;";
    private static final String OLD_READ = "(Lnet/minecraft/nbt/CompoundTag;ZLcom/simibubi/create/content/trains/graph/DimensionPalette;)V";
    private static final String NEW_READ = "(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;ZLcom/simibubi/create/content/trains/graph/DimensionPalette;)V";
    private static final String OLD_WRITE = "(Lnet/minecraft/nbt/CompoundTag;Lcom/simibubi/create/content/trains/graph/DimensionPalette;)V";
    private static final String NEW_WRITE = "(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;Lcom/simibubi/create/content/trains/graph/DimensionPalette;)V";
    private static final String USE_WITHOUT_ITEM = "(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/InteractionResult;";

    private static ClassNode node(String name) {
        ClassNode node = new ClassNode();
        node.name = name.replace('.', '/');
        return node;
    }

    private static MethodNode method(ClassNode node, String name, String desc) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) {
                return method;
            }
        }
        return null;
    }

    private static int calls(ClassNode node, String name, String desc) {
        int found = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && call.name.equals(name)
                    && call.desc.equals(desc)) {
                    found++;
                }
            }
        }
        return found;
    }

    private static MethodNode simple(String name, String desc, int returnOpcode) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, name, desc, null, null);
        if (returnOpcode == Opcodes.ARETURN) {
            method.instructions.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.GETSTATIC,
                "net/minecraft/world/ItemInteractionResult", "SUCCESS",
                "Lnet/minecraft/world/ItemInteractionResult;"));
        }
        method.instructions.add(new InsnNode(returnOpcode));
        return method;
    }

    @Test
    void abstractBogeyGetsARealLegacyUseAndDelegatingNewEntry() {
        ClassNode node = node(RailwaysInjectors.ABSTRACT_BOGEY);
        MethodNode syntheticUse = simple("useItemOn", NEW_USE, Opcodes.ARETURN);
        syntheticUse.maxLocals = 8;
        node.methods.add(syntheticUse);
        assertTrue(RailwaysInjectors.injectAbstractBogey(node));
        MethodNode legacy = method(node, "use", OLD_USE);
        assertNotNull(legacy, "real legacy use must exist");
        assertTrue(legacy.maxLocals >= 8);
        assertTrue(calls(node, "getItemInHand", "(Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/item/ItemStack;") >= 1,
            "legacy body must obtain the held stack from the player");
        MethodNode adapter = method(node, "useItemOn", NEW_USE);
        assertTrue(calls(node, "use", OLD_USE) >= 1, "new entry must really call the legacy entry");
        assertTrue(calls(node, "toItemInteractionResult", "(Lnet/minecraft/world/InteractionResult;)Lnet/minecraft/world/ItemInteractionResult;") >= 1);
        assertFalse(adapter.instructions.size() == 0, "adapter must dispatch, not be a shell");
        assertTrue(legacy.localVariables == null || legacy.localVariables.isEmpty(),
            "cloned body must not keep the stale LVT (duplicate attribute class error)");
        assertTrue(adapter.localVariables == null || adapter.localVariables.isEmpty(),
            "rewritten adapter must not keep the stale LVT");
        assertTrue(adapter.tryCatchBlocks.isEmpty(), "rewritten adapter must drop stale try/catch labels");
        int methods = node.methods.size();
        RailwaysInjectors.injectAbstractBogey(node);
        assertEquals(methods, node.methods.size(), "injection must be idempotent");
    }

    @Test
    void trackBlockLegacyEntriesExistButHaveNoRealCaller() {
        ClassNode node = node(RailwaysInjectors.TRACK_BLOCK);
        node.methods.add(simple("useItemOn", NEW_USE, Opcodes.ARETURN));
        assertTrue(RailwaysInjectors.injectTrackBlock(node));
        assertNotNull(method(node, "use", OLD_USE));
        assertNotNull(method(node, "extendedUse", OLD_USE));
        assertEquals(0, calls(node, "use", OLD_USE), "legacy use is a stale-injection target only");
        assertEquals(0, calls(node, "extendedUse", OLD_USE), "legacy extendedUse has no real caller");
        MethodNode emptyHand = method(node, "useWithoutItem", USE_WITHOUT_ITEM);
        assertNotNull(emptyHand, "real empty-hand entry must exist for the stale HEAD injection");
        assertEquals(org.objectweb.asm.Opcodes.ACC_PROTECTED, emptyHand.access & org.objectweb.asm.Opcodes.ACC_PROTECTED);
        assertEquals(0, calls(node, "useWithoutItem", USE_WITHOUT_ITEM),
            "empty hand is dispatched by the engine, not by another method in this class");
        int methods = node.methods.size();
        RailwaysInjectors.injectTrackBlock(node);
        assertEquals(methods, node.methods.size(), "injection must be idempotent");
    }

    @Test
    void globalStationLoadsThroughTheLegacyReadForRailwaysCallbacks() {
        ClassNode node = node(RailwaysInjectors.GLOBAL_STATION);
        MethodNode read = simple("read", NEW_READ, Opcodes.RETURN);
        node.methods.add(read);
        node.methods.add(simple("write", NEW_WRITE, Opcodes.RETURN));
        assertTrue(RailwaysInjectors.injectGlobalStation(node));
        assertNotNull(method(node, "read", OLD_READ), "legacy read must be present for the TAIL injection");
        assertEquals(1, calls(node, "read", OLD_READ),
            "real load path must call the legacy read exactly once");
        assertTrue(method(node, "read", OLD_READ).instructions.size() > 0);
        assertNotNull(method(node, "write", OLD_WRITE), "legacy write must exist for LimitEnabled");
        assertEquals(1, calls(node, "write", OLD_WRITE),
            "real save path must call the legacy write exactly once");
        int methods = node.methods.size();
        RailwaysInjectors.injectGlobalStation(node);
        assertEquals(methods, node.methods.size(), "injection must be idempotent");
        assertEquals(1, calls(node, "read", OLD_READ), "no duplicate load call");
        assertEquals(1, calls(node, "write", OLD_WRITE), "no duplicate save call");
    }

    @Test
    void resultConversionsRoundTrip() {
        for (net.minecraft.world.ItemInteractionResult item : net.minecraft.world.ItemInteractionResult.values()) {
            assertNotNull(RailwaysCompat.toInteractionResult(item));
            assertNotNull(RailwaysCompat.toItemInteractionResult(RailwaysCompat.toInteractionResult(item)));
        }
        assertEquals(net.minecraft.world.InteractionResult.SUCCESS,
            RailwaysCompat.toInteractionResult(net.minecraft.world.ItemInteractionResult.SUCCESS));
        assertEquals(net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION,
            RailwaysCompat.toItemInteractionResult(net.minecraft.world.InteractionResult.PASS));
    }

    @Test
    void pluginDispatchesTheThreeHooks() throws Exception {
        io.izzel.arclight.common.mod.ArclightMixinPlugin plugin =
            new io.izzel.arclight.common.mod.ArclightMixinPlugin();
        ClassNode bogey = node(RailwaysInjectors.ABSTRACT_BOGEY);
        bogey.methods.add(simple("useItemOn", NEW_USE, Opcodes.ARETURN));
        plugin.preApply(RailwaysInjectors.ABSTRACT_BOGEY, bogey,
            "io.izzel.arclight.common.mixin.core.compat.railways.AbstractBogeyBlockHook", null);
        assertNotNull(method(bogey, "use", OLD_USE));
        ClassNode station = node(RailwaysInjectors.GLOBAL_STATION);
        station.methods.add(simple("read", NEW_READ, Opcodes.RETURN));
        plugin.preApply(RailwaysInjectors.GLOBAL_STATION, station,
            "io.izzel.arclight.common.mixin.core.compat.railways.GlobalStationHook", null);
        assertNotNull(method(station, "read", OLD_READ));
    }

    @Test
    void carriageControlGetsARealCloserThanMarkerOnTheRealBranch() {
        ClassNode node = node(RailwaysInjectors.CARRIAGE_CONTRAPTION_ENTITY);
        MethodNode control = new MethodNode(Opcodes.ACC_PUBLIC, "control",
            "(Lnet/minecraft/core/BlockPos;Ljava/util/Collection;Lnet/minecraft/world/entity/player/Player;)Z",
            null, null);
        org.objectweb.asm.tree.LabelNode after = new org.objectweb.asm.tree.LabelNode();
        control.instructions.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
        control.instructions.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 3));
        control.instructions.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 1));
        control.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
            "net/createmod/catnip/math/VecHelper", "getCenterOf",
            "(Lnet/minecraft/core/Vec3i;)Lnet/minecraft/world/phys/Vec3;", false));
        control.instructions.add(new org.objectweb.asm.tree.LdcInsnNode(8.0D));
        control.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
            RailwaysInjectors.CARRIAGE_CONTRAPTION_ENTITY.replace('.', '/'),
            "canInteractWithBlock",
            "(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/phys/Vec3;D)Z", false));
        control.instructions.add(new org.objectweb.asm.tree.JumpInsnNode(Opcodes.IFNE, after));
        control.instructions.add(new InsnNode(Opcodes.ICONST_0));
        control.instructions.add(new InsnNode(Opcodes.IRETURN));
        control.instructions.add(after);
        control.instructions.add(new InsnNode(Opcodes.ICONST_1));
        control.instructions.add(new InsnNode(Opcodes.IRETURN));
        control.maxLocals = 4;
        control.maxStack = 6;
        node.methods.add(control);
        assertTrue(RailwaysInjectors.injectCarriageContraptionEntity(node));
        int closer = 0;
        int helper = 0;
        boolean hasBranch = false;
        for (AbstractInsnNode instruction : method(node, "control",
            "(Lnet/minecraft/core/BlockPos;Ljava/util/Collection;Lnet/minecraft/world/entity/player/Player;)Z").instructions) {
            if (instruction instanceof MethodInsnNode call && call.name.equals("closerThan")
                && call.desc.equals("(Lnet/minecraft/core/Position;D)Z")) {
                closer++;
            }
            if (instruction instanceof MethodInsnNode call && call.name.equals("canInteractWithBlock")) {
                helper++;
            }
            if (instruction.getOpcode() == Opcodes.IFEQ) {
                hasBranch = true;
            }
        }
        assertEquals(1, closer, "one real Vec3.closerThan marker in control");
        assertEquals(1, helper, "normal players still use the original helper");
        assertTrue(hasBranch, "the marker result must lead the real branch");
        int methods = node.methods.size();
        RailwaysInjectors.injectCarriageContraptionEntity(node);
        assertEquals(methods, node.methods.size(), "injection must be idempotent");
    }

    @Test
    void stationBlockRealEntryCallsLegacyUseOnceBeforeTheVanillaBody() {
        ClassNode node = node(RailwaysInjectors.STATION_BLOCK);
        MethodNode original = simple("useItemOn", NEW_USE, Opcodes.ARETURN);
        original.access = Opcodes.ACC_PROTECTED;
        original.maxLocals = 8;
        node.methods.add(original);
        assertTrue(RailwaysInjectors.injectStationBlock(node));
        assertNotNull(method(node, "use", OLD_USE), "legacy autoWhistle host must exist");
        MethodNode body = method(node, "arclight$stationUseItemOnBody", NEW_USE);
        assertNotNull(body, "original vanilla body must be preserved");
        MethodNode adapter = method(node, "useItemOn", NEW_USE);
        assertNotNull(adapter);
        int legacyCalls = calls(node, "use", OLD_USE);
        int bodyCalls = calls(node, "arclight$stationUseItemOnBody", NEW_USE);
        assertEquals(1, legacyCalls, "real entry calls the legacy host exactly once");
        assertEquals(1, bodyCalls, "vanilla body runs once, only when the legacy result did not handle");
        boolean branch = false;
        for (AbstractInsnNode instruction : adapter.instructions) {
            if (instruction.getOpcode() == Opcodes.IF_ACMPEQ) {
                branch = true;
            }
        }
        assertTrue(branch, "legacy return value must decide the real branch");
        assertTrue(adapter.localVariables == null || adapter.localVariables.isEmpty());
        int methods = node.methods.size();
        RailwaysInjectors.injectStationBlock(node);
        assertEquals(methods, node.methods.size(), "injection must be idempotent");
    }
}
