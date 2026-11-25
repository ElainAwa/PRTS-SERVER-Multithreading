/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.qualification;

import io.izzel.arclight.common.prts.kernel.random.SharedStreamTable;
import io.izzel.arclight.common.prts.kernel.random.StreamStateBinding;

import java.util.List;
import java.util.Map;

/** Runs the three references in one process using only execution entries installed by a window harness. */
public final class ReferenceDriver {

    private ReferenceDriver() {
    }

    /** Runs A, B and C in order over two decoded inputs; a restore mismatch refuses by name and stops. */
    public static ReferenceRunner.Report run(ReferenceInput captured, ReferenceInput live,
                                             boolean hostAuthorized, ReferenceDifferencePolicy policy) {
        ReferenceDifferencePolicy active = policy == null ? ReferenceDifferencePolicy.empty() : policy;
        // Passing no host adapter when unbound makes the A half NOT_RUN instead of claiming a live run.
        ReferenceRunner runner = new ReferenceRunner(
            entry(ReferenceRunner.Mode.B_NATIVE_SERIAL),
            entry(ReferenceRunner.Mode.C_OWNER_PARALLEL),
            HostSerialExecutor.bound() ? HostSerialExecutor.adapter() : null,
            active);
        return runner.runAll(captured, live, hostAuthorized, active);
    }

    /** Decodes two archival inputs before driving them; a damaged archive refuses by name. */
    public static ReferenceRunner.Report runArchived(byte[] capturedArchive, byte[] liveArchive,
                                                     boolean hostAuthorized,
                                                     ReferenceDifferencePolicy policy) {
        ReferenceInput captured = decode(capturedArchive);
        if (captured == null) {
            return refused("decode-failed:captured");
        }
        ReferenceInput live = decode(liveArchive);
        if (live == null) {
            return refused("decode-failed:live");
        }
        return run(captured, live, hostAuthorized, policy);
    }

    /** Drives two archives whose stream state is bound to a publication revision. */
    public static ReferenceRunner.Report runBounded(byte[] capturedArchive, byte[] liveArchive,
                                                    StreamStateBinding binding, boolean hostAuthorized,
                                                    ReferenceDifferencePolicy policy) {
        if (binding == null) {
            return refused("stream-state-binding:absent");
        }
        if (!binding.hasRevision()) {
            return refused("stream-state-binding:revision-absent");
        }
        try {
            new SharedStreamTable().restoreBounded(binding);
        } catch (IllegalStateException invalid) {
            return refused(invalid.getMessage());
        }
        ReferenceInput captured = decode(capturedArchive);
        if (captured == null) {
            return refused("decode-failed:captured");
        }
        // A captured input whose stream state is outside the binding is not a fixed input of that revision.
        if (!binding.states().contains(captured.streamState())) {
            return refused("stream-state-binding:input-state-not-bound");
        }
        ReferenceInput live = decode(liveArchive);
        if (live == null) {
            return refused("decode-failed:live");
        }
        return run(captured, live, hostAuthorized, policy);
    }

    private static ReferenceRunner.Entry entry(ReferenceRunner.Mode mode) {
        return new ReferenceRunner.Entry() {
            @Override
            public ReferenceRunner.Mode mode() {
                return mode;
            }

            @Override
            public DualReferenceParityHarness.Result run(ReferenceInput input) {
                DualReferenceParityHarness.Execution execution = mode == ReferenceRunner.Mode.B_NATIVE_SERIAL
                    ? NativeExecutionExecutor.serial() : NativeExecutionExecutor.parallel();
                return execution.run(input);
            }
        };
    }

    private static ReferenceInput decode(byte[] bytes) {
        ReferenceInputCodec.DecodeResult decoded = ReferenceInputCodec.decode(bytes);
        return decoded.ok() ? decoded.input() : null;
    }

    private static ReferenceRunner.Report refused(String reason) {
        return new ReferenceRunner.Report(null, List.of(), Map.of(), false,
            List.of(reason), List.of(), List.of(reason), false, "REFUSED", reason, true);
    }
}
