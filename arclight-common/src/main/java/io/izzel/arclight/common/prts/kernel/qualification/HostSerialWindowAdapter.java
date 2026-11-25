/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.qualification;

/** The installation point an authorised window harness uses to supply a real unmodified-host serial
 * runner. This class owns no implementation and derives no witness: until a harness installs one,
 * the adapter stays unavailable and a run refuses by the existing unbound reason. */
public final class HostSerialWindowAdapter {

    private HostSerialWindowAdapter() {
    }

    public static void install(HostSerialExecutor.SerialRun hostRun) {
        HostSerialExecutor.bind(hostRun);
    }

    public static void uninstall() {
        HostSerialExecutor.unbind();
    }

    public static boolean installed() {
        return HostSerialExecutor.bound();
    }

    public static ReferenceRunner.HostSerialReference adapter() {
        return HostSerialExecutor.adapter();
    }

    public static DualReferenceParityHarness.Result run(ReferenceInput input) {
        return adapter().run(input);
    }
}
