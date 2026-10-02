/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.waitpoints;
import io.izzel.arclight.common.prts.kernel.waitpoints.WaitPointRegistry.WaitPointDeclaration;

/** A producer, a progress signal, a timeout action and a degradation target: a row missing any of
 * them cannot be stored, because a wait whose progress cannot be read is the failure this registry
 * exists to prevent. */
public final class Dec19Elements {

    /** Kind of the progress signal of a wait point. */
    public enum SignalKind {
        COUNT,
        WATERMARK,
        HEARTBEAT
    }

    /** The progress signal of a wait point. */
    public record ProgressSignal(SignalKind kind, String fieldRef) {
    }

    private Dec19Elements() {
    }

    /** Returns the element a declaration is missing. */
    public static String missingElement(WaitPointDeclaration declaration) {
        if (declaration == null) {
            return "declaration";
        }
        return missingElement(declaration.producer(), declaration.signal(),
            declaration.timeoutAction(), declaration.degradeTo());
    }

    /** Returns the element a set of four is missing. */
    public static String missingElement(String producer, ProgressSignal signal, String timeoutAction,
                                        String degradeTo) {
        if (producer == null || producer.isBlank()) {
            return "producer";
        }
        if (signal == null || signal.fieldRef() == null || signal.fieldRef().isBlank()) {
            return "progress signal";
        }
        if (timeoutAction == null || timeoutAction.isBlank()) {
            return "timeout action";
        }
        if (degradeTo == null || degradeTo.isBlank()) {
            return "degrade target";
        }
        return null;
    }
}
