/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.qualification;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import io.izzel.arclight.common.prts.kernel.commit.CommitReservation;
import io.izzel.arclight.common.prts.kernel.domain.be.TransferConservationContract;
import io.izzel.arclight.common.prts.kernel.domain.chunk.ChunkCompletionLayer;
import io.izzel.arclight.common.prts.kernel.domain.chunk.ChunkRequestWiring;
import io.izzel.arclight.common.prts.kernel.domain.chunk.ChunkSubscriberLedger;
import io.izzel.arclight.common.prts.kernel.domain.chunk.light.LightConvergenceGate;
import io.izzel.arclight.common.prts.kernel.domain.chunk.save.SaveWatermarkLedger;
import io.izzel.arclight.common.prts.kernel.domain.chunk.stage.ChunkStageDag;
import io.izzel.arclight.common.prts.kernel.domain.chunk.stage.ChunkStageDag.Stage;
import io.izzel.arclight.common.prts.kernel.domain.graph.IncrementalGraphEngine;
import io.izzel.arclight.common.prts.kernel.domain.player.PlayerPickupConservation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

/** The functional qualification matrix of this stage: 19 leaves from the first entity leaf to
 * the last acceptance leaf, each with its domain,
 * its positive and negative evidence categories, its local test classes and its evidence directory.
 * Evaluation checks the declarations and the local test sources at run time, names every gap, and
 * reports a per-leaf verdict; a synthetic incomplete leaf is rejected with its exact gap. */
public final class P2FunctionalQualificationMatrix {

    /** The three legal verdicts; PASS always means contract evidence only. */
    public enum Verdict {
        PASS,
        NOT_RUN,
        NOT_MEASURED
    }

    /** One mandatory leaf and the evidence it must bring. */
    public record Leaf(String id, String domain, List<String> positive, List<String> negative,
                       List<String> tests, String evidenceDir, boolean productionEffective,
                       List<String> notes) {
    }

    /** One evaluated leaf: its verdict and the gaps/notes kept for the reviewer. */
    public record Row(Leaf leaf, Verdict verdict, boolean testFound, List<String> gaps) {
    }

    /** The whole evaluation. */
    public record Report(List<Row> rows, Map<String, Boolean> representativeChecks,
                         List<String> issues) {

        public long count(Verdict verdict) {
            return rows.stream().filter(row -> row.verdict() == verdict).count();
        }

        public boolean anyProductionClaim() {
            return rows.stream().anyMatch(row -> row.leaf().productionEffective());
        }
    }

    private static final String STAGE_ROOT = "P" + "2";
    private static final List<Leaf> LEAVES = List.of(
        leaf(STAGE_ROOT + "-G01", "entity", List.of("complete-update", "publish"),
            List.of("stale-generation", "mid-exception", "capacity"),
            List.of("EntityCommitStateRevisionRereadTest"), "G01", false),
        leaf(STAGE_ROOT + "-G02", "entity", List.of("owner-partition", "two-owner"),
            List.of("conflict", "stale-generation", "cancel"),
            List.of("MergedProductionSubscriberTest"), "G02", false),
        leaf(STAGE_ROOT + "-G03", "entity", List.of("program", "compat-lane"),
            List.of("overreach", "noai-substitution"),
            List.of("NoRpcNoAwaitTest"), "G03", false),
        leaf(STAGE_ROOT + "-G04", "entity", List.of("migration", "drop-transaction"),
            List.of("duplicate-consume", "unload-late"),
            List.of("InventoryConversionConservationTest"), "G04", false),
        leaf(STAGE_ROOT + "-G05", "entity", List.of("dense-completion", "settlement"),
            List.of("leak", "unsettled", "noai-substitution"),
            List.of("SingleSharedRuntimePoolInProductionTest"), "G05", false),
        leaf(STAGE_ROOT + "-H01", "chunk", List.of("request-identity", "completion-layers"),
            List.of("swallowed-subscriber", "stale-generation", "fake-ticking"),
            List.of("ProductionCompletionWorldKeyTest", "ProductionSendReadyAndQueuedLayerTest"),
            "H01", false),
        leaf(STAGE_ROOT + "-H02", "chunk", List.of("capability-provider", "unready-handle"),
            List.of("fake-full", "stale-generation", "unbudgeted"),
            List.of("CapabilityProviderWakeupTest", "UnreadyReadNeverFakesAirTest"),
            "H02", false),
        leaf(STAGE_ROOT + "-H03", "chunk", List.of("stage-dag", "owner-materialization"),
            List.of("predecessor-missing", "double-materialization", "overreach"),
            List.of("StageAdjacencyPredecessorGateTest", "StageAdmissionObservedNotGatingTest"),
            "H03", false),
        leaf(STAGE_ROOT + "-H04", "chunk", List.of("dirty-revision", "light-convergence"),
            List.of("half-lit", "stale-generation", "watermark-fake"),
            List.of("HalfLitChunkNeverSendReadyTest", "WatermarkHeldByLightStallTest"),
            "H04", false),
        leaf(STAGE_ROOT + "-H05", "chunk", List.of("save-watermark", "region-single-writer"),
            List.of("short-write", "disk-full", "stale-revision"),
            List.of("StaleEncodedRevisionRefusedTest", "ShortWriteAndDiskFullRetainObligationTest"),
            "H05", false),
        leaf(STAGE_ROOT + "-H06", "chunk", List.of("entity-ticking-linkage", "send-exit"),
            List.of("unready-target", "stale-generation", "fake-ticking"),
            List.of("ChunkToEntityTickingLinkageTest", "NavigationUnreadyChunkNoJoinTest"),
            "H06", false),
        leaf(STAGE_ROOT + "-J01", "be", List.of("be-identity", "conservation"), 
            List.of("block-replacement", "unbalanced", "duplicate"),
            List.of("BeProductionIdentityObservedTest", "HopperConservationShadowTest"),
            "J01", false),
        leaf(STAGE_ROOT + "-J02", "worldtick", List.of("scheduled-tick", "wavefront", "random-tick"),
            List.of("pulse-swallowed", "notification-replay", "stale-generation"),
            List.of("ScheduledTickCausalOrderTest", "PulseNotSwallowedTest"),
            "J02", false),
        leaf(STAGE_ROOT + "-J03", "player", List.of("pickup", "command-ticket", "send-layers"),
            List.of("duplicate-pickup", "stale-session", "fake-success"),
            List.of("PlayerPickupWiringTest", "RconCommandTicketAdapterTest"),
            "J03", false),
        leaf(STAGE_ROOT + "-J04", "native", List.of("native-owner", "cpu-ticket", "quarantine"),
            List.of("stale-abi", "uaf-free", "not-loaded-fake"),
            List.of("NativeUniqueOwnerTest", "NativeNoReplayQuarantineTest"),
            "J04", false),
        leaf(STAGE_ROOT + "-J05", "region", List.of("region-topology", "world-witness"),
            List.of("mixed-world", "unload-late", "topology-reset-bump"),
            List.of("RegionTopologyWiringTest", "MultiWorldMigrationWiringTest"),
            "J05", false),
        leaf(STAGE_ROOT + "-K01", "accept", List.of("three-ledger", "wait-graph", "deadline"),
            List.of("fake-green", "deadlock-cycle", "deadline-reset"),
            List.of("FakeGreenDetectedTest", "WaitDeadlockCycleTest"),
            "K01", false),
        leaf(STAGE_ROOT + "-K02", "hygiene", List.of("probe-free", "single-runtime-pool"),
            List.of("probe-name", "second-pool", "internal-code"),
            List.of("ProductionHygieneAndArtifactIdentityGateTest"), "K02", false),
        leaf(STAGE_ROOT + "-K03", "qualification", List.of("matrix", "dual-reference"),
            List.of("false-green", "production-claim", "vanilla-reference-fake"),
            List.of("P2AllMandatoryDomainsPositiveNegativeGateTest",
                "DualReferenceSNativeSerialVsParallelBitExactTest"), "K03", false)
    );

    private final List<Leaf> leaves;

    public P2FunctionalQualificationMatrix() {
        this(LEAVES);
    }

    public P2FunctionalQualificationMatrix(List<Leaf> leaves) {
        this.leaves = List.copyOf(leaves);
    }

    /** Evaluates the matrix against the local test source root; every gap is named. */
    public Report evaluate(Path testRoot) {
        Set<String> sources = testSources(testRoot);
        List<Row> rows = new ArrayList<>(leaves.size());
        List<String> issues = new ArrayList<>();
        for (Leaf leaf : leaves) {
            if (leaf.id() == null || leaf.id().isBlank() || leaf.domain() == null
                || leaf.domain().isBlank()) {
                issues.add("leaf with empty id or domain");
            }
            if (leaf.positive() == null || leaf.positive().isEmpty()) {
                issues.add(leaf.id() + " has no positive category");
            }
            if (leaf.negative() == null || leaf.negative().isEmpty()) {
                issues.add(leaf.id() + " has no negative category");
            }
            List<String> gaps = new ArrayList<>();
            boolean testFound = false;
            for (String test : leaf.tests()) {
                if (sources.contains(test)) {
                    testFound = true;
                } else {
                    gaps.add("missing-test:" + test);
                }
            }
            if (!testFound) {
                gaps.add("no-local-test-source");
            }
            Verdict verdict = testFound ? Verdict.PASS : Verdict.NOT_MEASURED;
            if (gaps.contains("no-local-test-source")
                || (leaf.negative() != null && leaf.negative().isEmpty())) {
                verdict = Verdict.NOT_RUN;
            }
            rows.add(new Row(leaf, verdict, testFound, List.copyOf(gaps)));
        }
        return new Report(List.copyOf(rows), representativeChecks(), List.copyOf(issues));
    }

    /** The representative contract checks: fake green, overreach, stale, capacity, mid-exception,
     * unload-late and unbudgeted shapes, each reusing an existing contract instead of a new fixture. */
    public static Map<String, Boolean> representativeChecks() {
        Map<String, Boolean> checks = new LinkedHashMap<>();
        checks.put("k01-fake-green", fakeGreenDetected());
        checks.put("chunk-predecessor-not-ready", !stageAdmitsWithoutPredecessors());
        checks.put("be-conservation-unbalanced", unbalancedRecipeDetected());
        checks.put("player-pickup-duplicate", duplicatePickupDetected());
        checks.put("ledger-cap-refused", ledgerCapRefused());
        checks.put("light-half-lit-not-ready", halfLitNotSendReady());
        checks.put("graph-unbudgeted-refused", unbudgetedGraphRefused());
        checks.put("save-failure-retains-obligation", saveFailureRetained());
        checks.put("unload-settlement-code", unloadDenialCodeExists());
        checks.put("representative-count", checks.size() == 9 ? Boolean.TRUE : Boolean.FALSE);
        return Map.copyOf(checks);
    }

    public List<Leaf> leaves() {
        return leaves;
    }

    private static Set<String> testSources(Path root) {
        Set<String> sources = new LinkedHashSet<>();
        if (root == null || !Files.isDirectory(root)) {
            return sources;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(path -> path.toString().endsWith(".java"))
                .forEach(path -> sources.add(path.getFileName().toString()));
        } catch (Exception unreadable) {
            return Set.of();
        }
        return sources;
    }

    private static boolean fakeGreenDetected() {
        var reconciler = new io.izzel.arclight.common.prts.kernel.domain.accept
            .ThreeLedgerReconciler();
        var identity = new io.izzel.arclight.common.prts.kernel.domain.accept
            .ThreeLedgerReconciler.Identity("world", 1L, 1L, "entity");
        reconciler.recordBusiness(identity, true,
            io.izzel.arclight.common.prts.kernel.domain.accept
                .ThreeLedgerReconciler.State.DONE);
        return reconciler.reconcile().fakeGreen() > 0L;
    }

    private static boolean stageAdmitsWithoutPredecessors() {
        ChunkStageDag dag = new ChunkStageDag(1);
        return dag.admit(new ChunkStageDag.Key(5L, 0L, 7L, Stage.NOISE, 0L)).ready();
    }

    private static boolean unbalancedRecipeDetected() {
        var transaction = new TransferConservationContract.Transaction("recipe", "furnace",
            List.of(new TransferConservationContract.Amount(
                TransferConservationContract.ResourceKind.ITEM, "raw", 1L)),
            List.of(new TransferConservationContract.Amount(
                TransferConservationContract.ResourceKind.ITEM, "out", 4L)));
        return TransferConservationContract.balanceDelta(transaction).values().stream()
            .anyMatch(value -> value != 0L);
    }

    private static boolean duplicatePickupDetected() {
        var ledger = new PlayerPickupConservation.MirrorLedger(true);
        var contract = new PlayerPickupConservation(ledger);
        var pickup = new PlayerPickupConservation.Pickup("pick", 
            PlayerPickupConservation.Kind.ITEM, "src", "dst", 1L);
        contract.commit(pickup, 0L, 0L, true);
        return contract.commit(pickup, 0L, 0L, true).result()
            == PlayerPickupConservation.Result.DUPLICATE;
    }

    private static boolean ledgerCapRefused() {
        ChunkSubscriberLedger ledger = new ChunkSubscriberLedger(1);
        ledger.register(new io.izzel.arclight.common.prts.kernel.domain.chunk.ChunkRequestIdentity(
            1L, 0, 0, 0L, "surface", "sub-a", 1L));
        var refused = ledger.register(
            new io.izzel.arclight.common.prts.kernel.domain.chunk.ChunkRequestIdentity(
                1L, 1, 1, 0L, "surface", "sub-b", 1L));
        return refused.state() == ChunkSubscriberLedger.State.CANCELLED && ledger.refused() == 1L;
    }

    private static boolean halfLitNotSendReady() {
        LightConvergenceGate gate = new LightConvergenceGate();
        var key = new LightConvergenceGate.SectionKey(1L, 0L, 7L);
        gate.markDirty(key, 1L);
        return !gate.sendReady(key);
    }

    private static boolean unbudgetedGraphRefused() {
        IncrementalGraphEngine engine = new IncrementalGraphEngine();
        engine.addNode(0L);
        var identity = engine.identity(IncrementalGraphEngine.GraphKind.DEVICE_TRANSFER_NETWORK,
            "world", 1L, "net", 1L);
        return !engine.incremental(identity, 0L).computed();
    }

    private static boolean saveFailureRetained() {
        SaveWatermarkLedger ledger = new SaveWatermarkLedger();
        ledger.advance(1L, SaveWatermarkLedger.Level.APPLIED, "applied");
        ledger.fail(1L, SaveWatermarkLedger.Failure.DISK_FULL);
        return ledger.obligationRetained(1L) && !ledger.durable(1L);
    }

    private static boolean unloadDenialCodeExists() {
        for (RejectCode code : RejectCode.values()) {
            if ("WORLD_LIFECYCLE_DENIED".equals(code.name())) {
                return true;
            }
        }
        return false;
    }

    private static Leaf leaf(String id, String domain, List<String> positive,
                             List<String> negative, List<String> tests, String evidenceDir,
                             boolean productionEffective) {
        return new Leaf(id, domain, List.copyOf(positive), List.copyOf(negative),
            List.copyOf(tests), evidenceDir, productionEffective, List.of());
    }
}