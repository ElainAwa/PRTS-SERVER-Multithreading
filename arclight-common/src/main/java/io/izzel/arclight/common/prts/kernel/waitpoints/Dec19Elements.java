/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.waitpoints;

/**
 * The four elements every wait point has to declare, and the check that they are there.
 *
 * <p>A producer, a progress signal, a timeout action and a degradation target: a row missing any of
 * them cannot be stored, because a wait whose progress cannot be read is the failure this registry
 * exists to prevent. The signal carries one of three shapes - a counter, a watermark or a
 * heartbeat - so a reader always knows how progress is observed, not only that it is.</p>
 */
public final class Dec19Elements {

    /** Kind of the progress signal of a wait point. */
    public enum SignalKind {
        /** A counter that advances while the waited path makes progress. */
        COUNT,
        /** A watermark that falls while the waited path drains. */
        WATERMARK,
        /** A heartbeat that keeps ticking while the waited path is alive. */
        HEARTBEAT
    }

    /**
     * The progress signal of a wait point.
     *
     * @param kind     one of the three shapes
     * @param fieldRef the reading that carries the signal
     */
    public record ProgressSignal(SignalKind kind, String fieldRef) {
    }

    private Dec19Elements() {
    }

    /**
     * Returns the element a declaration is missing.
     *
     * @param declaration the declaration to check
     * @return the missing element, or {@code null} when the row is complete
     */
    public static String missingElement(WaitPointDeclaration declaration) {
        if (declaration == null) {
            return "declaration";
        }
        return missingElement(declaration.producer(), declaration.signal(),
            declaration.timeoutAction(), declaration.degradeTo());
    }

    /**
     * Returns the element a set of four is missing.
     *
     * @param producer      who produces the thing that is waited for
     * @param signal        how progress of that producer is observed
     * @param timeoutAction what happens when the wait runs out of time
     * @param degradeTo     what the wait degrades to when it cannot proceed
     * @return the missing element, or {@code null} when all four are present
     */
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
