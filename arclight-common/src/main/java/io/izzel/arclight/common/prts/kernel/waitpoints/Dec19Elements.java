/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.waitpoints;
import io.izzel.arclight.common.prts.kernel.waitpoints.WaitPointRegistry.WaitPointDeclaration;

/** A producer, a progress signal, a timeout action and a degradation target: a row missing any of
 * them cannot be stored, because a wait whose progress cannot be read is the failure this prevents. */
public final class Dec19Elements {

    public enum SignalKind {
        COUNT,
        WATERMARK,
        HEARTBEAT
    }

    public record ProgressSignal(SignalKind kind, String fieldRef) {
    }

    private Dec19Elements() {
    }

    public static String missingElement(WaitPointDeclaration declaration) {
        if (declaration == null) {
            return "declaration";
        }
        return missingElement(declaration.producer(), declaration.signal(),
            declaration.timeoutAction(), declaration.degradeTo());
    }

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
