package io.izzel.arclight.common.mod.mixins;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Writes the real seven-argument creeper burst directly into {@code explodeCreeper}'s instruction
 * list during preApply, before later mixin configs scan that method for injection points. */
public final class CreeperSevenArgInjector {

    public static final String TARGET = "net.minecraft.world.entity.monster.Creeper";
    public static final String HOOK = "io.izzel.arclight.common.mixin.core.world.entity.monster.CreeperEarlyHook";
    private static final String LEVEL = "net/minecraft/world/level/Level";
    private static final String ENTITY = "net/minecraft/world/entity/Entity";
    private static final String CREEPER = "net/minecraft/world/entity/monster/Creeper";
    private static final String BRIDGE = "io/izzel/arclight/common/bridge/core/world/entity/monster/CreeperBridge";
    private static final String SIX = "(Lnet/minecraft/world/entity/Entity;DDDFLnet/minecraft/world/level/Level$ExplosionInteraction;)Lnet/minecraft/world/level/Explosion;";
    private static final String SEVEN = "(Lnet/minecraft/world/entity/Entity;DDDFZLnet/minecraft/world/level/Level$ExplosionInteraction;)Lnet/minecraft/world/level/Explosion;";

    private CreeperSevenArgInjector() {
    }

    public static boolean inject(ClassNode targetClass) {
        if (targetClass == null) {
            return false;
        }
        for (MethodNode method : targetClass.methods) {
            if (!method.name.equals("explodeCreeper") || !method.desc.equals("()V")) {
                continue;
            }
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && call.owner.equals(LEVEL)
                    && call.name.equals("explode") && call.desc.equals(SEVEN)) {
                    return false;
                }
            }
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && call.owner.equals(LEVEL)
                    && call.name.equals("explode") && call.desc.equals(SIX)) {
                    AbstractInsnNode cursor = instruction.getNext();
                    while (cursor != null && (cursor.getOpcode() == -1
                        || cursor.getOpcode() == Opcodes.POP && false)) {
                        cursor = cursor.getNext();
                    }
                    if (cursor == null || cursor.getOpcode() != Opcodes.POP) {
                        throw new IllegalStateException("six-argument explode result is not popped");
                    }
                    method.instructions.insert(cursor, burst());
                    return true;
                }
            }
        }
        return false;
    }

    private static InsnList burst() {
        InsnList list = new InsnList();
        list.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
        list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ENTITY, "level",
            "()Lnet/minecraft/world/level/Level;", false));
        list.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
        list.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
        list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ENTITY, "getX", "()D", false));
        list.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
        list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ENTITY, "getY", "()D", false));
        list.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
        list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ENTITY, "getZ", "()D", false));
        list.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
        list.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, BRIDGE, "arclight$eventRadius", "()F", true));
        list.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
        list.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, BRIDGE, "arclight$eventFire", "()Z", true));
        list.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.GETSTATIC,
            "net/minecraft/world/level/Level$ExplosionInteraction", "MOB",
            "Lnet/minecraft/world/level/Level$ExplosionInteraction;"));
        list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LEVEL, "explode", SEVEN, false));
        list.add(new InsnNode(Opcodes.POP));
        return list;
    }
}
