/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.kernel.auth.OwnerRegistry;
import io.izzel.arclight.common.prts.kernel.auth.OwnerToken;
import io.izzel.arclight.common.prts.kernel.auth.WriteAttempt;
import io.izzel.arclight.common.prts.kernel.auth.WriteAuthority;
import io.izzel.arclight.common.prts.kernel.codes.WriteDisposition;
import io.izzel.arclight.common.prts.kernel.auth.WriteLedger;
import io.izzel.arclight.common.prts.kernel.auth.WriteLevel;
import io.izzel.arclight.common.prts.kernel.auth.WriteVerdict;
import io.izzel.arclight.common.prts.kernel.auth.HolderKind;
import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import io.izzel.arclight.common.prts.kernel.codes.RejectTrigger;
import io.izzel.arclight.common.prts.kernel.intent.CommitOrder;
import io.izzel.arclight.common.prts.kernel.intent.CommitSegment;
import io.izzel.arclight.common.prts.kernel.intent.IntentQueue;
import io.izzel.arclight.common.prts.kernel.intent.WriteIntent;
import io.izzel.arclight.common.prts.kernel.sites.IntentPayloadDirectory;
import io.izzel.arclight.common.prts.kernel.sites.WritePath;
import io.izzel.arclight.common.prts.kernel.sites.WritePathCounters;
import io.izzel.arclight.common.prts.kernel.sites.ThreadOrigin;
import io.izzel.arclight.common.prts.kernel.sites.WorldWriteGuard;
import io.izzel.arclight.common.prts.kernel.waitpoints.SiteRegisterResult;
import io.izzel.arclight.common.prts.kernel.waitpoints.WaitSite;
import io.izzel.arclight.common.prts.kernel.shares.OverrunRecord;
import io.izzel.arclight.common.prts.kernel.shares.ShareClass;
import io.izzel.arclight.common.prts.kernel.shares.SharePlanner;
import io.izzel.arclight.common.prts.kernel.shares.ShareTable;
import io.izzel.arclight.common.prts.kernel.waitpoints.CoverageReport;
import io.izzel.arclight.common.prts.kernel.waitpoints.Dec19Elements;
import io.izzel.arclight.common.prts.kernel.waitpoints.RegisterResult;
import io.izzel.arclight.common.prts.kernel.waitpoints.WaitObservation;
import io.izzel.arclight.common.prts.kernel.waitpoints.WaitPointDeclaration;
import io.izzel.arclight.common.prts.kernel.waitpoints.WaitPointRegistry;
import io.izzel.arclight.common.prts.kernel.waitpoints.WaitSpan;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A self-check that drives the decision matrix of the four pieces on scratch objects.
 *
 * <p>The check never touches the live counters: it builds its own registry, queue and ledger,
 * drives the paths a call site would drive and prints what came back. That makes it usable on a
 * running server without polluting the readout, and it gives an acceptance run one command whose
 * output states the matrix in numbers.</p>
 */
public final class KernelSelfCheck {

    private KernelSelfCheck() {
    }

    /**
     * Runs the matrix.
     *
     * @return one {@code name=value} line per result, ending with the verdict of the check
     */
    public static List<String> run() {
        List<String> lines = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        Set<RejectCode> writeCodes = new HashSet<>();
        for (RejectTrigger trigger : RejectTrigger.values()) {
            if (trigger.scope() == RejectTrigger.Scope.WRITE && trigger.code() != null) {
                writeCodes.add(trigger.code());
            }
        }
        lines.add("selftest.triggers=" + RejectTrigger.values().length);
        lines.add("selftest.codes=" + RejectCode.values().length);
        lines.add("selftest.write_codes=" + writeCodes.size());
        lines.add("selftest.new_codes=0");

        OwnerRegistry owners = new OwnerRegistry();
        IntentQueue intents = new IntentQueue(() -> 2);
        WriteLedger ledger = new WriteLedger();
        WriteAuthority authority = new WriteAuthority(owners, intents, ledger, () -> false, () -> 2);
        long tick = 100L;
        owners.acquire(new OwnerToken("world", WriteLevel.REGION, "region-1", 1L, 7L, tick,
            tick + 50L, HolderKind.REGISTERED, "site:a"));
        owners.acquire(new OwnerToken("world", WriteLevel.REGION, "region-1", 2L, 9L, tick,
            tick + 50L, HolderKind.REGISTERED, "site:b"));

        Map<WriteDisposition, Integer> verdicts = new EnumMap<>(WriteDisposition.class);
        count(verdicts, authority.authorize(grantedAttempt()));
        count(verdicts, authority.authorize(attempt(2L, "site:b", "world", "world",
            WriteLevel.REGION, "region-1", HolderKind.REGISTERED, true, 7L, tick, 0L)));
        count(verdicts, authority.authorize(attempt(3L, "site:a", "world", "world",
            WriteLevel.REGION, "region-1", HolderKind.REGISTERED, true, 6L, tick, 0L)));
        count(verdicts, authority.authorize(attempt(4L, "site:a", "world", "other",
            WriteLevel.REGION, "region-1", HolderKind.REGISTERED, true, 7L, tick, 0L)));
        count(verdicts, authority.authorize(attempt(5L, "site:c", "world", "world",
            WriteLevel.REGION, "region-1", HolderKind.REGISTERED, false, 7L, tick, 0L)));
        count(verdicts, authority.authorize(attempt(6L, "site:a", "world", "world",
            WriteLevel.REGION, "region-2", HolderKind.REGISTERED, true, 7L, tick, 0L)));
        count(verdicts, authority.authorize(attempt(7L, "site:a", "world", "world",
            WriteLevel.REGION, "region-1", HolderKind.REGISTERED, true, 7L, tick, 0L)
            .toBuilder().lifecycle(true, false).build()));
        count(verdicts, authority.authorize(WriteAttempt.builder(8L, "world", "site:a")
            .holder(HolderKind.REGISTERED, "site:a", "thread-a")
            .target(WriteLevel.REGION, "region-1").version(7L, tick, 0L).admitted(true)
            .wallClockRead(true).build()));
        count(verdicts, authority.authorize(WriteAttempt.builder(9L, "world", "site:a")
            .holder(HolderKind.REGISTERED, "site:a", "thread-a").read()
            .target(WriteLevel.REGION, "region-1").version(7L, tick, 0L).admitted(true).build()));
        count(verdicts, authority.authorize(attempt(10L, "unregistered:t:0", "world", "world",
            WriteLevel.REGION, "region-9", HolderKind.UNREGISTERED, true, 7L, tick, 1L)));
        count(verdicts, authority.authorize(attempt(11L, "unregistered:t:0", "world", "world",
            WriteLevel.REGION, "region-9", HolderKind.UNREGISTERED, true, 7L, tick, 2L)));
        WriteVerdict fullQueue = authority.authorize(attempt(12L, "unregistered:t:0", "world",
            "world", WriteLevel.REGION, "region-9", HolderKind.UNREGISTERED, true, 7L, tick, 3L));
        count(verdicts, fullQueue);
        count(verdicts, authority.authorize(attempt(13L, "site:a", "world", "world",
            WriteLevel.DIMENSION, "region-1", HolderKind.REGISTERED, true, 7L, tick, 1L)));

        lines.add("selftest.verdict_grant=" + verdicts.getOrDefault(WriteDisposition.GRANT, 0));
        lines.add("selftest.verdict_intent=" + verdicts.getOrDefault(WriteDisposition.INTENT, 0));
        lines.add("selftest.verdict_deny=" + verdicts.getOrDefault(WriteDisposition.DENY, 0));
        lines.add("selftest.unregistered_grant=" + ledger.unregisteredGrants());
        lines.add("selftest.closure=" + (ledger.verifyClosure() ? "ok" : "broken"));
        lines.add("selftest.double_holder=" + owners.doubleHolderCount());
        lines.add("selftest.intent_rejected_full=" + intents.rejectedFullCount());

        if (verdicts.getOrDefault(WriteDisposition.GRANT, 0) != 2) {
            failures.add("expected two grants, saw " + verdicts.get(WriteDisposition.GRANT));
        }
        if (verdicts.getOrDefault(WriteDisposition.INTENT, 0) != 2) {
            failures.add("expected two intents, saw " + verdicts.get(WriteDisposition.INTENT));
        }
        if (ledger.unregisteredGrants() != 0L) {
            failures.add("an unregistered write was granted");
        }
        if (!ledger.verifyClosure()) {
            failures.add("the write accounting does not close");
        }
        if (owners.doubleHolderCount() != 1L) {
            failures.add("a second holder was not refused");
        }
        if (fullQueue.code() != RejectCode.QUEUE_CAP_EXCEEDED) {
            failures.add("a full intent queue did not refuse");
        }

        WriteLedger enforcedLedger = new WriteLedger();
        WriteAuthority enforced = new WriteAuthority(new OwnerRegistry(), new IntentQueue(() -> 8),
            enforcedLedger, () -> true, () -> 0);
        WriteVerdict refused = enforced.authorize(attempt(20L, "unregistered:t:1", "world", "world",
            WriteLevel.REGION, "region-9", HolderKind.UNREGISTERED, true, 1L, tick, 0L));
        if (refused.disposition() != WriteDisposition.DENY
            || refused.code() != RejectCode.WRITE_DENIED_NOT_OWNER) {
            failures.add("enforcement did not refuse an unregistered write");
        }
        intents.bindPayload(intent -> null);
        CommitOrder outOfOrder = intents.commit(1L, tick);
        CommitOrder first = intents.commit(0L, tick);
        CommitOrder second = intents.commit(1L, tick);
        if (outOfOrder.code() != RejectCode.COMMIT_ORDER_VIOLATION) {
            failures.add("an out-of-order commit was not refused");
        }
        if (!first.committed() || !second.committed()) {
            failures.add("the frozen order did not commit");
        }
        lines.add("selftest.intent_order_violations=" + intents.orderViolationCount());
        lines.add("selftest.intent_order_committed=" + intents.committedCount());

        SharePlanner shares = new SharePlanner();
        Map<String, EnumMap<ShareClass, Double>> used = Map.of("world", usedRow(10.0));
        ShareTable table = shares.plan(List.of("world"), tick, used);
        ShareTable.ShareRow row = table.row("world", ShareClass.ENTITY);
        OverrunRecord record = shares.record(row, "site:a", tick);
        shares.recordWouldDegrade(record);
        lines.add("selftest.share_overrun_class=" + shares.classOverrunTotal());
        lines.add("selftest.share_overrun_world=" + shares.worldOverrunTotal());
        lines.add("selftest.share_action_executed=" + (shares.anyActionExecuted() ? 1 : 0));
        lines.add("selftest.share_margin_ms=" + String.format(Locale.ROOT, "%.3f", row.marginMs()));
        lines.add("selftest.share_conservation="
            + (shares.checkConservation(table).ok() ? "ok" : "over"));
        if (shares.classOverrunTotal() != shares.worldOverrunTotal()) {
            failures.add("the two overrun dimensions do not sum to the same total");
        }
        if (shares.anyActionExecuted()) {
            failures.add("an overrun executed an action");
        }
        if (row.marginMs() >= 0.0) {
            failures.add("an overrun row did not publish a negative margin");
        }

        WaitPointRegistry waits = new WaitPointRegistry(() -> 50);
        RegisterResult missing = waits.registerWaitPoint(new WaitPointDeclaration("custom",
            "custom wait", "", new Dec19Elements.ProgressSignal(Dec19Elements.SignalKind.COUNT,
            "progress.custom.count"), "postpone", "snapshot", "per world", "custom.wait", 1));
        RegisterResult duplicate = waits.registerWaitPoint(builtinDeclaration());
        RegisterResult stored = waits.registerWaitPoint(new WaitPointDeclaration("custom",
            "custom wait", "custom producer",
            new Dec19Elements.ProgressSignal(Dec19Elements.SignalKind.WATERMARK,
                "progress.custom.watermark"), "postpone", "snapshot", "per world", "custom.wait", 1));
        WaitObservation overrun = waits.observeWait("chunk", new WaitSpan("chunk", "unknown.call",
            "site:a", "world", tick, 80L, "7"));
        waits.observeWait(null, new WaitSpan(null, "unknown.call", "site:a", "world", tick, 10L,
            "0"));
        CoverageReport coverage = waits.reportCoverage();
        boolean missingElement = missing instanceof RegisterResult.MissingElement;
        boolean duplicateId = duplicate instanceof RegisterResult.DuplicateWpId;
        boolean storedRow = stored instanceof RegisterResult.Ok;
        lines.add("selftest.wait_missing_element=" + (missingElement ? 1 : 0));
        lines.add("selftest.wait_duplicate=" + (duplicateId ? 1 : 0));
        lines.add("selftest.wait_registered=" + (storedRow ? 1 : 0));
        lines.add("selftest.wait_unregistered=" + coverage.unregistered());
        lines.add("selftest.wait_overrun=" + waits.waitOverrunCount());
        lines.add("selftest.wait_coverage_pct="
            + String.format(Locale.ROOT, "%.1f", coverage.coveragePct()));
        if (!missingElement) {
            failures.add("an incomplete wait point was stored");
        }
        if (!duplicateId) {
            failures.add("a duplicate wait point identity was accepted");
        }
        if (coverage.unregistered() != 1) {
            failures.add("an unregistered wait was not counted");
        }
        if (!overrun.overrun() || overrun.maxWaitMs() != 80L) {
            failures.add("a wait over the bound was not reported");
        }

        lines.addAll(commitSegmentMatrix(failures, tick));
        lines.addAll(writePathMatrix(failures, tick));
        lines.addAll(siteCoverage(failures, waits));

        lines.add("selftest.failures=" + failures.size());
        for (String failure : failures) {
            lines.add("selftest.failure=" + failure);
        }
        lines.add("selftest.result=" + (failures.isEmpty() ? "ok" : "failed"));
        return lines;
    }

    /** Drives the real write paths on scratch objects: short path, long path and enforcement. */
    private static List<String> writePathMatrix(List<String> failures, long tick) {
        List<String> lines = new ArrayList<>();
        WritePathCounters counters = new WritePathCounters();
        WriteLedger ledger = new WriteLedger();
        OwnerRegistry owners = new OwnerRegistry();
        IntentPayloadDirectory payloads = new IntentPayloadDirectory();
        IntentQueue intents = new IntentQueue(() -> 8);
        WriteAuthority authority = new WriteAuthority(owners, intents, ledger, () -> true, () -> 2);
        WorldWriteGuard guard = new WorldWriteGuard(counters, authority, intents, payloads, ledger);
        intents.bindPayload(guard);
        CommitSegment segment = new CommitSegment(intents, () -> true, intents::capacity);
        guard.bindServerThread(Thread.currentThread(), "host:server-thread");
        guard.refresh(true, false, false, tick);
        Object levelRef = new Object();

        int shortPath = guard.classifyBlockWrite(levelRef);
        WorkerWrite observed = new WorkerWrite(guard, levelRef);
        runOnWorker(observed);
        lines.add("selftest.path_short=" + (shortPath == 0 ? "pass" : "judge"));
        lines.add("selftest.path_worker_verdict=" + (observed.verdict == 1 ? "judge" : "pass"));
        lines.add("selftest.path_worker_proceeded=" + (observed.proceeded ? 1 : 0));
        lines.add("selftest.path_worker_intent="
            + counters.count(WritePath.BLOCK_WRITE, ThreadOrigin.WORKER, HolderKind.UNREGISTERED,
                WriteDisposition.INTENT));
        lines.add("selftest.path_main_grant="
            + counters.count(WritePath.BLOCK_WRITE, ThreadOrigin.MAIN, HolderKind.REGISTERED,
                WriteDisposition.GRANT));

        guard.refresh(true, true, false, tick);
        WorkerWrite enforced = new WorkerWrite(guard, levelRef);
        runOnWorker(enforced);
        lines.add("selftest.path_enforce_proceeded=" + (enforced.proceeded ? 1 : 0));
        lines.add("selftest.path_enforce_denied="
            + counters.count(WritePath.BLOCK_WRITE, ThreadOrigin.WORKER, HolderKind.UNREGISTERED,
                WriteDisposition.DENY));

        guard.refresh(true, false, true, tick);
        WorkerWrite handedOver = new WorkerWrite(guard, levelRef);
        runOnWorker(handedOver);
        CommitSegment.Pass pass = segment.run(tick);
        boolean committed = pass.ran() && pass.code() == null && pass.steps() == 1;
        lines.add("selftest.path_handed_over=" + (handedOver.proceeded ? 0 : 1));
        lines.add("selftest.path_commit_committed=" + (committed ? 1 : 0));
        lines.add("selftest.path_commit_steps=" + pass.steps());
        lines.add("selftest.path_payload_applied=" + (handedOver.deferredApplied ? 1 : 0));
        lines.add("selftest.path_commit_entry="
            + counters.count(WritePath.KERNEL_COMMIT, ThreadOrigin.MAIN, HolderKind.KERNEL,
                WriteDisposition.GRANT));
        lines.add("selftest.path_closure=" + (counters.closureHolds() ? "ok" : "broken"));

        if (shortPath != 0) {
            failures.add("the server thread did not take the short write path");
        }
        if (observed.verdict != 1 || !observed.proceeded) {
            failures.add("a worker write did not take the long path and pass while observing");
        }
        if (counters.count(WritePath.BLOCK_WRITE, ThreadOrigin.WORKER, HolderKind.UNREGISTERED,
            WriteDisposition.GRANT) != 0L) {
            failures.add("an undeclared writer was granted at a real write path");
        }
        if (enforced.proceeded) {
            failures.add("enforcement let an undeclared write through");
        }
        if (!committed || !handedOver.deferredApplied) {
            failures.add("the handed-over write was not applied by the commit segment");
        }
        if (!counters.closureHolds()) {
            failures.add("the write path accounting does not close");
        }
        return lines;
    }

    /**
     * Drives the commit segment on scratch objects: while its switch is off nothing is consumed, and
     * while it is on the queue drains in the order the channel froze.
     */
    private static List<String> commitSegmentMatrix(List<String> failures, long tick) {
        List<String> lines = new ArrayList<>();
        IntentQueue queue = new IntentQueue(() -> 8);
        List<String> applied = new ArrayList<>();
        queue.bindPayload(intent -> {
            applied.add(intent.payloadHandle());
            return null;
        });
        queue.enqueue(intent(1L, "first"));
        queue.enqueue(intent(2L, "second"));
        CommitSegment holding = new CommitSegment(queue, () -> false, queue::capacity);

        CommitSegment.Pass held = holding.run(tick);
        int heldDepth = queue.depth();
        long heldExecuted = queue.executedCount();
        lines.add("selftest.intent_hold_ran=" + (held.ran() ? 1 : 0));
        lines.add("selftest.intent_hold_depth=" + heldDepth);
        lines.add("selftest.intent_hold_mode=" + holding.mode());
        lines.add("selftest.intent_hold_executed=" + heldExecuted);

        CommitSegment walking = new CommitSegment(queue, () -> true, queue::capacity);
        CommitSegment.Pass walked = walking.run(tick);
        lines.add("selftest.intent_walk_steps=" + walked.steps());
        lines.add("selftest.intent_walk_executed=" + queue.executedCount());
        lines.add("selftest.intent_walk_depth=" + queue.depth());
        lines.add("selftest.intent_walk_cursor=" + walking.cursor());
        lines.add("selftest.intent_walk_last_exec_tick=" + queue.lastExecTick());
        lines.add("selftest.intent_walk_order_violations=" + queue.orderViolationCount());
        lines.add("selftest.intent_walk_applied=" + String.join(",", applied));

        if (held.ran() || heldDepth != 2 || heldExecuted != 0L) {
            failures.add("the commit segment consumed something while its switch was off");
        }
        if (walked.steps() != 2 || queue.executedCount() != 2L || queue.depth() != 0) {
            failures.add("the commit segment did not drain the frozen order");
        }
        if (!applied.equals(List.of("first", "second"))) {
            failures.add("the commit segment did not apply the frozen order");
        }
        if (queue.lastExecTick() != tick) {
            failures.add("the commit segment did not publish the tick it executed on");
        }
        queue.enqueue(intent(3L, "third"));
        CommitOrder outOfOrder = queue.commit(7L, tick);
        if (outOfOrder.code() != RejectCode.COMMIT_ORDER_VIOLATION || queue.orderViolationCount() != 1L) {
            failures.add("an order the channel never froze was not refused");
        }
        return lines;
    }

    private static WriteIntent intent(long id, String handle) {
        return WriteIntent.draft(id, "world", "world", "block_write", 0L, handle, "xdomain",
            "site:a");
    }

    /** Drives the written-down call site list: completeness, refusal and coverage. */
    private static List<String> siteCoverage(List<String> failures, WaitPointRegistry waits) {
        List<String> lines = new ArrayList<>();
        CoverageReport coverage = waits.reportCoverage();
        SiteRegisterResult missing = waits.registerSite(new WaitSite("incomplete", "chunk",
            "example.Incomplete", "run", "entity_tick", "other", "",
            new Dec19Elements.ProgressSignal(Dec19Elements.SignalKind.COUNT, "progress.incomplete"),
            "postpone", "snapshot", "self check", 1));
        SiteRegisterResult stored = waits.registerSite(new WaitSite("extra", "chunk",
            "example.Extra", "run", "entity_tick", "other", "chunk materialization pipeline",
            new Dec19Elements.ProgressSignal(Dec19Elements.SignalKind.COUNT,
                "progress.chunk.materialized_per_tick"), "forced materialization convergence",
            "read-only snapshot", "self check", 1));
        lines.add("selftest.site_total=" + coverage.siteInventoryTotal());
        lines.add("selftest.site_registered=" + coverage.siteRegistered());
        lines.add("selftest.site_uncovered=" + coverage.siteUncoveredIds().size());
        lines.add("selftest.site_coverage_pct="
            + String.format(Locale.ROOT, "%.1f", coverage.siteCoveragePct()));
        lines.add("selftest.site_missing_element="
            + (missing instanceof SiteRegisterResult.MissingElement ? 1 : 0));
        lines.add("selftest.site_stored=" + (stored instanceof SiteRegisterResult.Ok ? 1 : 0));
        if (coverage.siteInventoryTotal() < 1) {
            failures.add("the call site list is empty");
        }
        if (coverage.siteRegistered() != coverage.siteInventoryTotal()) {
            failures.add("a listed call site does not carry all four elements");
        }
        if (!coverage.siteUncoveredIds().isEmpty()) {
            failures.add("a listed call site has no wait point row");
        }
        if (!(missing instanceof SiteRegisterResult.MissingElement)) {
            failures.add("a call site missing an element was stored");
        }
        return lines;
    }

    /** Drives one write attempt from a thread that is not the thread that bound the guard. */
    private static void runOnWorker(WorkerWrite write) {
        Thread thread = new Thread(write, "selftest-worker");
        thread.start();
        try {
            thread.join();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** One write attempt driven from a thread that is not the server thread. */
    private static final class WorkerWrite implements Runnable {

        private final WorldWriteGuard guard;
        private final Object levelRef;
        private int verdict = -1;
        private boolean proceeded;
        private volatile boolean deferredApplied;

        private WorkerWrite(WorldWriteGuard guard, Object levelRef) {
            this.guard = guard;
            this.levelRef = levelRef;
        }

        @Override
        public void run() {
            verdict = guard.classifyBlockWrite(levelRef);
            proceeded = guard.admitBlockWrite(levelRef, "world", () -> {
                deferredApplied = true;
                return true;
            });
        }
    }

    private static EnumMap<ShareClass, Double> usedRow(double used) {
        EnumMap<ShareClass, Double> row = new EnumMap<>(ShareClass.class);
        row.put(ShareClass.ENTITY, used);
        return row;
    }

    private static WaitPointDeclaration builtinDeclaration() {
        return new WaitPointDeclaration("chunk", "duplicate", "producer",
            new Dec19Elements.ProgressSignal(Dec19Elements.SignalKind.COUNT,
                "progress.duplicate.count"), "postpone", "snapshot", "per world", "duplicate", 1);
    }

    private static WriteAttempt grantedAttempt() {
        return attempt(1L, "site:a", "world", "world", WriteLevel.REGION, "region-1",
            HolderKind.REGISTERED, true, 7L, 100L, 0L);
    }

    private static WriteAttempt attempt(long id, String site, String world, String declaredWorld,
                                        WriteLevel level, String domain, HolderKind kind,
                                        boolean admitted, long version, long tick, long order) {
        return WriteAttempt.builder(id, world, site)
            .holder(kind, site, "thread-" + site)
            .target(level, domain)
            .declaredWorld(declaredWorld)
            .domains(Set.of(domain), Set.of(domain))
            .version(version, tick, order)
            .admitted(admitted)
            .build();
    }

    private static void count(Map<WriteDisposition, Integer> verdicts, WriteVerdict verdict) {
        verdicts.merge(verdict.disposition(), 1, Integer::sum);
    }
}
