package io.izzel.arclight.boot.asm;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Experiment-only: route the entity random factory to a stable-key helper when the diagnostic
 * property is set. The default build keeps the vanilla factory and its System.nanoTime seed. */
public final class PrtsStableEntityRandom implements Implementer {

    private static final String OWNER = "net/minecraft/world/entity/Entity";
    private static final String HELPER =
        "io/izzel/arclight/common/prts/kernel/domain/entity/owner/StableEntityRandom";
    public static final String FACTORY_DESC = "()Lnet/minecraft/util/RandomSource;";

    @Override
    public boolean processClass(ClassNode node) {
        if (!OWNER.equals(node.name)) return false;
        boolean changed = false;
        for (MethodNode method : node.methods) {
            if (!"<init>".equals(method.name)) continue;
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && "net/minecraft/util/RandomSource".equals(call.owner)
                    && "create".equals(call.name) && FACTORY_DESC.equals(call.desc)) {
                    call.owner = HELPER;
                    // The vanilla factory is an interface static call (InterfaceMethodref). Once the
                    // owner is a class, the constant-pool tag must become Methodref: clear itf or the
                    // JVM rejects the call site with IncompatibleClassChangeError at construction.
                    call.itf = false;
                    changed = true;
                }
            }
        }
        return changed;
    }
}