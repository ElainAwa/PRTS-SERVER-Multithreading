/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.qualification;

/** The bindable host serial executor: a future authorised window harness binds the real unmodified
 * host serial path here. While nothing is bound the adapter reports unavailable and its run refuses
 * with host-serial-adapter-unbound; this class never installs a fake host or a synchronous stub. */
public final class HostSerialExecutor {

    /** The real unmodified host serial runner the window harness binds. */
    @FunctionalInterface
    public interface SerialRun {
        DualReferenceParityHarness.Result run(ReferenceInput input);
    }

    private static volatile SerialRun bound;

    private HostSerialExecutor() {
    }

    /** Binds the real host serial path; a null binding is refused rather than treated as fake. */
    public static synchronized void bind(SerialRun executor) {
        if (executor == null) {
            throw new IllegalArgumentException("host serial executor is null");
        }
        bound = executor;
    }

    /** Releases the binding; the adapter turns unavailable again. */
    public static synchronized void unbind() {
        bound = null;
    }

    public static boolean bound() {
        return bound != null;
    }

    public static String status() {
        return bound != null ? "bound" : "unbound";
    }

    public static boolean boundTo(SerialRun executor) {
        return bound == executor;
    }

    /** The A-mode adapter over the current binding. */
    public static ReferenceRunner.HostSerialReference adapter() {
        return new Adapter();
    }

    /** The adapter itself: unbound calls refuse by name, bound calls use the real executor. */
    public static final class Adapter implements ReferenceRunner.HostSerialReference {

        @Override
        public boolean available() {
            return bound != null;
        }

        @Override
        public DualReferenceParityHarness.Result run(ReferenceInput input) {
            SerialRun current = bound;
            if (current == null) {
                throw new ReferenceRunner.ReferenceRefusal("host-serial-adapter-unbound");
            }
            return current.run(input);
        }
    }
}
