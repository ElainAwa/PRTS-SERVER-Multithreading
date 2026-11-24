/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.owner;

/** The phase of one owner batch's split body. Owner: the Server thread only. The batch binds a
 * phase in a try/finally around each pass and unbinds it before the next pass; the read-wave worker
 * never reads this context, it only reads the frozen {@code NativeMoveRead} snapshot. There is no
 * cross-thread visibility contract because no other thread observes the field. */
public final class NativeMovePhaseContext {

    private static final ThreadLocal<Boolean> PHASE_A = new ThreadLocal<>();

    private NativeMovePhaseContext() {
    }

    /** Binds the phase-A pass; the caller must unbind in a finally block on the same thread. */
    public static void beginPhaseA() {
        PHASE_A.set(Boolean.TRUE);
    }

    /** Binds the phase-B pass; the caller must unbind in a finally block on the same thread. */
    public static void beginPhaseB() {
        PHASE_A.set(Boolean.FALSE);
    }

    /** Removes the phase binding; a missing binding is a harmless no-op. */
    public static void end() {
        PHASE_A.remove();
    }

    /** Whether the current thread is running a phase-A pass; unbound means no split batch. */
    public static boolean inPhaseA() {
        return Boolean.TRUE.equals(PHASE_A.get());
    }

    /** Whether this thread is inside a split batch at all. */
    public static boolean bound() {
        return PHASE_A.get() != null;
    }
}
