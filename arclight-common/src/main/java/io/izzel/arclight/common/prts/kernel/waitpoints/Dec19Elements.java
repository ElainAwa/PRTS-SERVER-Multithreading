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
        if (declaration.producer() == null || declaration.producer().isBlank()) {
            return "producer";
        }
        if (declaration.signal() == null || declaration.signal().fieldRef() == null
            || declaration.signal().fieldRef().isBlank()) {
            return "progress signal";
        }
        if (declaration.timeoutAction() == null || declaration.timeoutAction().isBlank()) {
            return "timeout action";
        }
        if (declaration.degradeTo() == null || declaration.degradeTo().isBlank()) {
            return "degrade target";
        }
        return null;
    }
}
