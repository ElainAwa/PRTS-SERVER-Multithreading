/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.qualification;

/** The bindable real owner execution entry of the two native comparison sides. An authorised
 * window harness installs the implementation that drives the real owner phase over one fixed due
 * window in serial or parallel mode and gathers the witness from live level state. Without a
 * binding both modes refuse by name; no arithmetic fixture or synthetic witness is installed. */
public final class NativeExecutionExecutor {

    /** The real owner execution of one fixed input in one named mode. */
    @FunctionalInterface
    public interface OwnerRun {
        DualReferenceParityHarness.Result run(ReferenceInput input, ReferenceRunner.Mode mode);
    }

    private static volatile OwnerRun bound;

    private NativeExecutionExecutor() {
    }

    /** Binds the real owner execution; a null binding is refused rather than treated as a stub. */
    public static synchronized void bind(OwnerRun executor) {
        if (executor == null) {
            throw new IllegalArgumentException("the owner execution is null");
        }
        bound = executor;
    }

    /** Releases the binding; both modes turn unbound again. */
    public static synchronized void unbind() {
        bound = null;
    }

    public static boolean bound() {
        return bound != null;
    }

    public static String status() {
        return bound != null ? "bound" : "unbound";
    }

    public static boolean boundTo(OwnerRun executor) {
        return bound == executor;
    }

    /** The B-side execution adapter the harness binds; it refuses by name while unbound. */
    public static DualReferenceParityHarness.Execution serial() {
        return input -> execute(input, ReferenceRunner.Mode.B_NATIVE_SERIAL);
    }

    /** The C-side execution adapter the harness binds; it refuses by name while unbound. */
    public static DualReferenceParityHarness.Execution parallel() {
        return input -> execute(input, ReferenceRunner.Mode.C_OWNER_PARALLEL);
    }

    private static DualReferenceParityHarness.Result execute(ReferenceInput input,
                                                              ReferenceRunner.Mode mode) {
        OwnerRun current = bound;
        if (current == null) {
            throw new ReferenceRunner.ReferenceRefusal("owner-execution-unbound");
        }
        return current.run(input, mode);
    }
}
