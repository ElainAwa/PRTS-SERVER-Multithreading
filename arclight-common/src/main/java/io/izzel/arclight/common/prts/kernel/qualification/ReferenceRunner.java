/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.qualification;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** The sequential three-reference runner: A is the unmodified host serial adapter (a real entry the
 * future authorised window can call, never a placeholder), B is the S-Native serial reference and C
 * is the owner-parallel run over the same fixed input. The three modes run one after another, not as
 * three world mirrors; B and C must be bit-exact, and while the A live run is not authorised the
 * full three-reference pass stays false with A named NOT_RUN. */
public final class ReferenceRunner {

    /** The three reference modes. */
    public enum Mode {
        A_HOST_SERIAL,
        B_NATIVE_SERIAL,
        C_OWNER_PARALLEL
    }

    /** One executable reference entry: B and C run on the offline fixture/contracts. */
    public interface Entry {
        Mode mode();

        DualReferenceParityHarness.Result run(ReferenceInput input);
    }

    /** The real host-serial adapter path; it is absent until a future authorised window supplies it. */
    public interface HostSerialReference {
        boolean available();

        DualReferenceParityHarness.Result run(ReferenceInput input);
    }

    /** One mode's result with its named status. */
    public record ModeResult(Mode mode, String status, DualReferenceParityHarness.Result result,
                             String reason) {
    }

    /** The whole three-reference report. */
    public record Report(ReferenceInput reference, List<Mode> executionOrder,
                         Map<Mode, ModeResult> results, boolean bCExact,
                         List<String> differences, boolean fullThreeReferencePass,
                         String aStatus, String aReason, boolean restoreRefused) {
    }

    private final Entry nativeSerial;
    private final Entry ownerParallel;
    private final HostSerialReference hostSerial;

    /** The runner over the two offline entries; the A host adapter starts absent. */
    public ReferenceRunner(Entry nativeSerial, Entry ownerParallel) {
        this(nativeSerial, ownerParallel, null);
    }

    /** The runner with an explicit real host-serial adapter path (still not live-authorised). */
    public ReferenceRunner(Entry nativeSerial, Entry ownerParallel,
                           HostSerialReference hostSerial) {
        this.nativeSerial = Objects.requireNonNull(nativeSerial, "nativeSerial");
        this.ownerParallel = Objects.requireNonNull(ownerParallel, "ownerParallel");
        this.hostSerial = hostSerial;
    }

    /** The offline fixture runner: B uses the harness serial reference, C its owner-parallel run. */
    public static ReferenceRunner offlineFixture() {
        return new ReferenceRunner(offlineFixtureEntryFor(Mode.B_NATIVE_SERIAL),
            offlineFixtureEntryFor(Mode.C_OWNER_PARALLEL));
    }

    /** The offline entry of one non-host mode; the A mode has no fixture entry. */
    public static Entry offlineFixtureEntryFor(Mode mode) {
        if (mode == Mode.B_NATIVE_SERIAL) {
            return new Entry() {
                @Override
                public Mode mode() {
                    return Mode.B_NATIVE_SERIAL;
                }

                @Override
                public DualReferenceParityHarness.Result run(ReferenceInput input) {
                    return DualReferenceParityHarness.serial(toHarness(input));
                }
            };
        }
        if (mode == Mode.C_OWNER_PARALLEL) {
            return new Entry() {
                @Override
                public Mode mode() {
                    return Mode.C_OWNER_PARALLEL;
                }

                @Override
                public DualReferenceParityHarness.Result run(ReferenceInput input) {
                    return DualReferenceParityHarness.parallel(toHarness(input));
                }
            };
        }
        throw new IllegalArgumentException("the host serial mode needs a real adapter");
    }

    /** Runs the three modes sequentially over one fixed input; a restore mismatch refuses first. */
    public Report runAll(ReferenceInput captured, ReferenceInput live, boolean hostAuthorized) {
        if (captured == null) {
            return refused("restore-failed:no-capture");
        }
        ReferenceInput.RestoreResult restored = captured.restore(live);
        if (!restored.ok()) {
            return refused(restored.failure());
        }
        ReferenceInput input = restored.restored();
        List<Mode> order = new ArrayList<>(List.of(Mode.A_HOST_SERIAL, Mode.B_NATIVE_SERIAL,
            Mode.C_OWNER_PARALLEL));
        Map<Mode, ModeResult> results = new LinkedHashMap<>();
        ModeResult a = hostSerial != null && hostAuthorized && hostSerial.available()
            ? new ModeResult(Mode.A_HOST_SERIAL, "PASS", hostSerial.run(input), "")
            : new ModeResult(Mode.A_HOST_SERIAL, "NOT_RUN", null,
                hostSerial == null ? "host-serial-adapter-absent"
                    : "host-serial-live-not-authorized");
        results.put(Mode.A_HOST_SERIAL, a);
        ModeResult b = execute(nativeSerial, input);
        results.put(Mode.B_NATIVE_SERIAL, b);
        ModeResult c = execute(ownerParallel, input);
        results.put(Mode.C_OWNER_PARALLEL, c);
        List<String> differences = "PASS".equals(b.status()) && "PASS".equals(c.status())
            ? fieldDifferences(b.result(), c.result()) : List.of("mode-not-executed");
        boolean bCExact = differences.isEmpty();
        List<String> aDifferences = "PASS".equals(a.status())
            ? fieldDifferences(a.result(), b.result()) : List.of();
        boolean full = "PASS".equals(a.status()) && bCExact && aDifferences.isEmpty();
        return new Report(input, List.copyOf(order), Map.copyOf(results), bCExact,
            List.copyOf(differences), full, a.status(), a.reason(), false);
    }

    /** The differences between two witnesses, by field name; an empty list is bit-exact. */
    public static List<String> fieldDifferences(DualReferenceParityHarness.Result left,
                                                DualReferenceParityHarness.Result right) {
        List<String> differences = new ArrayList<>();
        if (left == null || right == null) {
            return List.of("missing-result");
        }
        if (!left.state().equals(right.state())) {
            differences.add("state");
        }
        if (left.streamPosition() != right.streamPosition()) {
            differences.add("streamPosition");
        }
        if (!left.poiOwner().equals(right.poiOwner())) {
            differences.add("poiOwner");
        }
        if (!left.events().equals(right.events())) {
            differences.add("events");
        }
        if (!left.inventory().equals(right.inventory())) {
            differences.add("inventory");
        }
        if (left.publishedRevision() != right.publishedRevision()) {
            differences.add("publishedRevision");
        }
        if (!left.manifest().equals(right.manifest())) {
            differences.add("manifest");
        }
        if (!left.digest().equals(right.digest())) {
            differences.add("digest");
        }
        return List.copyOf(differences);
    }

    private ModeResult execute(Entry entry, ReferenceInput input) {
        try {
            return new ModeResult(entry.mode(), "PASS", entry.run(input), "");
        } catch (Throwable failure) {
            return new ModeResult(entry.mode(), "REFUSED", null,
                "entry-failed:" + failure.getClass().getSimpleName());
        }
    }

    private static Report refused(String failure) {
        return new Report(null, List.of(), Map.of(), false, List.of(failure), false, "REFUSED",
            failure, true);
    }

    private static DualReferenceParityHarness.Input toHarness(ReferenceInput input) {
        return new DualReferenceParityHarness.Input(input.worldSeed(), input.registrationVersion(),
            input.keys(), Math.max(1, input.owners()));
    }
}
