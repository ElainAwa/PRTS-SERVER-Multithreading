package io.izzel.arclight.boot.asm;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.MethodInsnNode;

/**
 * Recognises the call site that runs the navigation block, both as the original virtual call and as
 * the redirect handler call the mixin pass leaves behind, so the stage extraction applies whichever
 * transform pass sees the class.
 */
public final class PrtsStageAnchors {

    static final String NAV = "net/minecraft/world/entity/ai/navigation/PathNavigation";

    private PrtsStageAnchors() {
    }

    /** Whether the instruction is the navigation block call in either shape. */
    public static boolean isNavigationCall(MethodInsnNode call) {
        if (call.getOpcode() == Opcodes.INVOKEVIRTUAL && NAV.equals(call.owner)) {
            return "tick".equals(call.name) && "()V".equals(call.desc);
        }
        return call.name.endsWith("prts$navigationSpan")
            && ("(L" + NAV + ";)V").equals(call.desc);
    }
}
