package io.izzel.arclight.boot.asm;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** Splits {@code LivingEntity.tick} after {@code aiStep} at {@code refreshDirtyAttributes}: the
 * prefix becomes an owner-declarable stage and the scale branch stays inline in the host. */
public final class PrtsLivingEntityTickTail implements Implementer {

    private static final String OWNER = "net/minecraft/world/entity/LivingEntity";
    private static final String STAGE = "prts$livingTickTailPreScale";
    private static final String INTERFACE =
        "io/izzel/arclight/common/prts/kernel/domain/entity/owner/LivingEntityTickTailPreScaleStages";
    private static final String PROGRAM =
        "io/izzel/arclight/common/prts/kernel/domain/entity/owner/LivingEntityTickTailPreScaleProgram";

    @Override
    public boolean processClass(ClassNode node) {
        if (!OWNER.equals(node.name)) {
            return false;
        }
        if (hasStage(node)) {
            return false;
        }
        return TailRegionExtractor.extractProgramSplit(node, "tick", "()V",
            call -> call.getOpcode() == Opcodes.INVOKEVIRTUAL
                && "aiStep".equals(call.name) && "()V".equals(call.desc),
            call -> call.getOpcode() == Opcodes.INVOKEVIRTUAL
                && "refreshDirtyAttributes".equals(call.name) && "()V".equals(call.desc),
            STAGE, INTERFACE, PROGRAM);
    }

    private static boolean hasStage(ClassNode node) {
        for (org.objectweb.asm.tree.MethodNode method : node.methods) {
            if (STAGE.equals(method.name)) {
                return true;
            }
        }
        return false;
    }
}
