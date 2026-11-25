/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.qualification;

/** Named installation surface for real execution entries; nothing is installed by default. */
public final class WindowExecutionBinder {

    /** The capability a harness supplies to put entity and world state back to a fixed input. */
    public interface InProcessReset {
        /** Returns null on success or a named reason when the state cannot be restored. */
        String restore(ReferenceInput input);
    }

    /** One named installation outcome, with the component and the refusal reason. */
    public record Installation(String component, String status, String reason) {

        public boolean installed() {
            return "INSTALLED".equals(status);
        }
    }

    private static final String RESET_ABSENT = "NOT_MEASURED:in-process-entity-world-reset-API";
    private static final String HOST_ABSENT = "NOT_MEASURED:real-host-serial-execution-entry";
    private static final String OWNER_ABSENT = "NOT_MEASURED:real-owner-execution-entry";

    private static volatile InProcessReset reset;

    private WindowExecutionBinder() {
    }

    /** Installs a real reset capability; an absent one is refused by name. */
    public static synchronized Installation installReset(InProcessReset capability) {
        if (capability == null) {
            return new Installation("in-process-reset", "REFUSED", RESET_ABSENT);
        }
        reset = capability;
        return new Installation("in-process-reset", "INSTALLED", "");
    }

    /** Reports the reset prerequisite, which refuses until a real capability is installed. */
    public static Installation requireReset() {
        return reset == null ? new Installation("in-process-reset", "REFUSED", RESET_ABSENT)
            : new Installation("in-process-reset", "INSTALLED", "");
    }

    /** Installs a real unmodified-host entry for A once a reset exists; no stand-in is installed. */
    public static synchronized Installation installHostSerial(HostSerialExecutor.SerialRun realHostRun) {
        Installation prerequisite = requireReset();
        if (!prerequisite.installed()) {
            return prerequisite;
        }
        if (realHostRun == null) {
            return new Installation("host-serial", "REFUSED", HOST_ABSENT);
        }
        HostSerialExecutor.bind(realHostRun);
        return new Installation("host-serial", "INSTALLED", "");
    }

    /** Installs one real owner entry for B and C and points both comparison adapters at it. */
    public static synchronized Installation installOwnerExecution(NativeExecutionExecutor.OwnerRun realOwnerRun) {
        Installation prerequisite = requireReset();
        if (!prerequisite.installed()) {
            return prerequisite;
        }
        if (realOwnerRun == null) {
            return new Installation("owner-execution", "REFUSED", OWNER_ABSENT);
        }
        NativeExecutionExecutor.bind(realOwnerRun);
        DualReferenceParityHarness.bind(ReferenceRunner.Mode.B_NATIVE_SERIAL,
            NativeExecutionExecutor.serial());
        DualReferenceParityHarness.bind(ReferenceRunner.Mode.C_OWNER_PARALLEL,
            NativeExecutionExecutor.parallel());
        return new Installation("owner-execution", "INSTALLED", "");
    }

    /** Names every still-absent capability without installing anything. */
    public static Installation prepareRealExecution() {
        StringBuilder missing = new StringBuilder();
        if (reset == null) {
            missing.append(RESET_ABSENT);
        }
        if (!HostSerialExecutor.bound()) {
            append(missing, HOST_ABSENT);
        }
        if (!NativeExecutionExecutor.bound()) {
            append(missing, OWNER_ABSENT);
        }
        if (missing.length() == 0) {
            return new Installation("real-execution", "INSTALLED", "");
        }
        return new Installation("real-execution", "REFUSED", missing.toString());
    }

    public static boolean resetInstalled() {
        return reset != null;
    }

    public static boolean hostSerialInstalled() {
        return HostSerialExecutor.bound();
    }

    public static boolean ownerExecutionInstalled() {
        return NativeExecutionExecutor.bound();
    }

    /** Releases every binding so independent runs cannot inherit an entry or a reset. */
    public static synchronized void uninstallAll() {
        reset = null;
        HostSerialExecutor.unbind();
        NativeExecutionExecutor.unbind();
        DualReferenceParityHarness.unbindAll();
    }

    private static void append(StringBuilder missing, String reason) {
        if (missing.length() > 0) {
            missing.append(';');
        }
        missing.append(reason);
    }
}
