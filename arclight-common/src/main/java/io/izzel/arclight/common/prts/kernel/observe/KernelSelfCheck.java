/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.kernel.KernelDomain;
import io.izzel.arclight.common.prts.kernel.KernelModule;
import io.izzel.arclight.common.prts.kernel.auth.OwnerRegistry;
import io.izzel.arclight.common.prts.kernel.auth.OwnerToken;
import io.izzel.arclight.common.prts.kernel.auth.WriteAttempt;
import io.izzel.arclight.common.prts.kernel.auth.WriteAuthority;
import io.izzel.arclight.common.prts.kernel.codes.WriteDisposition;
import io.izzel.arclight.common.prts.kernel.auth.WriteLedger;
import io.izzel.arclight.common.prts.kernel.auth.WriteLevel;
import io.izzel.arclight.common.prts.kernel.auth.WriteVerdict;
import io.izzel.arclight.common.prts.kernel.auth.HolderKind;
import io.izzel.arclight.common.prts.kernel.codes.DegradeLevel;
import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import io.izzel.arclight.common.prts.kernel.codes.RejectTrigger;
import io.izzel.arclight.common.prts.kernel.commit.CommitLog;
import io.izzel.arclight.common.prts.kernel.commit.CommitRing;
import io.izzel.arclight.common.prts.kernel.degrade.DegradeLadder;
import io.izzel.arclight.common.prts.kernel.exits.DualExits;
import io.izzel.arclight.common.prts.kernel.safety.SafetyNet;
import io.izzel.arclight.common.prts.kernel.safety.ZeroEffectDetector;
import io.izzel.arclight.common.prts.kernel.jobs.JobDeclaration;
import io.izzel.arclight.common.prts.kernel.jobs.JobGraph;
import io.izzel.arclight.common.prts.kernel.jobs.JobGraphBuilder;
import io.izzel.arclight.common.prts.kernel.jobs.JobIntake;
import io.izzel.arclight.common.prts.kernel.jobs.JobScheduler;
import io.izzel.arclight.common.prts.kernel.jobs.ShareMeterPoint;
import io.izzel.arclight.common.prts.kernel.plan.TickPlan;
import io.izzel.arclight.common.prts.kernel.plan.TickPlanPlanner;
import io.izzel.arclight.common.prts.kernel.plan.TickPlanStore;
import io.izzel.arclight.common.prts.kernel.intent.CommitOrder;
import io.izzel.arclight.common.prts.kernel.intent.CommitSegment;
import io.izzel.arclight.common.prts.kernel.intent.IntentPayload;
import io.izzel.arclight.common.prts.kernel.intent.IntentQueue;
import io.izzel.arclight.common.prts.kernel.intent.WriteIntent;
import io.izzel.arclight.common.prts.kernel.sites.IntentPayloadDirectory;
import io.izzel.arclight.common.prts.kernel.sites.WritePath;
import io.izzel.arclight.common.prts.kernel.sites.WritePathCounters;
import io.izzel.arclight.common.prts.kernel.sites.ThreadOrigin;
import io.izzel.arclight.common.prts.kernel.sites.WorldWriteGuard;
import io.izzel.arclight.common.prts.kernel.waitpoints.SiteInventory.SiteRegisterResult;
import io.izzel.arclight.common.prts.kernel.waitpoints.WaitSite;
import io.izzel.arclight.common.prts.kernel.meter.SelfClass;
import io.izzel.arclight.common.prts.kernel.shares.BudgetStateMachine;
import io.izzel.arclight.common.prts.kernel.shares.OverrunRecord;
import io.izzel.arclight.common.prts.kernel.shares.ShareClass;
import io.izzel.arclight.common.prts.kernel.shares.ShareMeter;
import io.izzel.arclight.common.prts.kernel.shares.SharePlanner;
import io.izzel.arclight.common.prts.kernel.shares.SharePlanner.ConservationCheck;
import io.izzel.arclight.common.prts.kernel.shares.ShareTable;
import io.izzel.arclight.common.prts.kernel.waitpoints.CoverageReport;
import io.izzel.arclight.common.prts.kernel.waitpoints.Dec19Elements;
import io.izzel.arclight.common.prts.kernel.waitpoints.ForcedConvergence;
import io.izzel.arclight.common.prts.kernel.waitpoints.WaitProgress;
import io.izzel.arclight.common.prts.kernel.waitpoints.WaitPointRegistry.RegisterResult;
import io.izzel.arclight.common.prts.kernel.waitpoints.WaitPointRegistry.WaitObservation;
import io.izzel.arclight.common.prts.kernel.waitpoints.WaitPointRegistry.WaitPointDeclaration;
import io.izzel.arclight.common.prts.kernel.waitpoints.WaitPointRegistry;
import io.izzel.arclight.common.prts.kernel.waitpoints.WaitPointRegistry.WaitSpan;
import io.izzel.arclight.common.prts.kernel.waitpoints.observe.WaitSiteObserver;
import io.izzel.arclight.common.prts.kernel.arena.ArenaLedger;
import io.izzel.arclight.common.prts.kernel.arena.ArenaPassthrough;
import io.izzel.arclight.common.prts.kernel.arena.ArenaSlot;
import io.izzel.arclight.common.prts.kernel.diff.DiffProbe;
import io.izzel.arclight.common.prts.kernel.diff.DomainHash;
import io.izzel.arclight.common.prts.kernel.diff.HashWhitelist;
import io.izzel.arclight.common.prts.kernel.diff.StateHasher;
import io.izzel.arclight.common.prts.kernel.waitpoints.SiteInventory.WaitClass;
import io.izzel.arclight.common.prts.kernel.waitpoints.WaitLadder;
import io.izzel.arclight.common.prts.support.PrtsWaitSites;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

/** The check never touches the live counters: it builds its own registry, queue and ledger, drives
 * the paths a call site would drive and prints what came back. */
public final class KernelSelfCheck {

    /** The domain identity the differential arms are hashed under. */
    private static final String DOMAIN_ID = "entity";

    private KernelSelfCheck() {
    }

    /** Runs the matrix. */
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
        IntentQueue intents = new IntentQueue(() -> 2, () -> 0);
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
        WriteAuthority enforced = new WriteAuthority(new OwnerRegistry(),
            new IntentQueue(() -> 8, () -> 0), enforcedLedger, () -> true, () -> 0);
        WriteVerdict refused = enforced.authorize(attempt(20L, "unregistered:t:1", "world", "world",
            WriteLevel.REGION, "region-9", HolderKind.UNREGISTERED, true, 1L, tick, 0L));
        if (refused.disposition() != WriteDisposition.DENY
            || refused.code() != RejectCode.WRITE_DENIED_NOT_OWNER) {
            failures.add("enforcement did not refuse an unregistered write");
        }
        intents.bindPayload(intent -> IntentPayload.Outcome.APPLIED);
        CommitOrder outOfOrder = intents.commit("world", 1L, tick);
        CommitOrder first = intents.commit("world", 0L, tick);
        CommitOrder second = intents.commit("world", 1L, tick);
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
        lines.addAll(retryAndLifecycleMatrix(failures, tick));
        lines.addAll(writePathMatrix(failures, tick));
        lines.addAll(siteCoverage(failures, waits));
        lines.addAll(waitSiteMatrix(failures, tick));
        lines.addAll(waitContractMatrix(failures, tick));
        lines.addAll(waitLadderMatrix(failures, tick));
        lines.addAll(arenaMatrix(failures, tick));
        lines.addAll(armMatrix(failures, tick));
        lines.addAll(budgetGovernanceMatrix(failures, tick));
        lines.addAll(contractLayerMatrix(failures, tick));
        lines.addAll(domainSelfChecks(failures, tick));

        lines.add("selftest.failures=" + failures.size());
        for (String failure : failures) {
            lines.add("selftest.failure=" + failure);
        }
        lines.add("selftest.result=" + (failures.isEmpty() ? "ok" : "failed"));
        return lines;
    }

    private static List<String> writePathMatrix(List<String> failures, long tick) {
        List<String> lines = new ArrayList<>();
        WritePathCounters counters = new WritePathCounters();
        WriteLedger ledger = new WriteLedger();
        OwnerRegistry owners = new OwnerRegistry();
        IntentPayloadDirectory payloads = new IntentPayloadDirectory();
        IntentQueue intents = new IntentQueue(() -> 8, () -> 2);
        WriteAuthority authority = new WriteAuthority(owners, intents, ledger, () -> true, () -> 2);
        WorldWriteGuard guard = new WorldWriteGuard(counters, authority, intents, payloads, ledger);
        intents.bindPayload(guard);
        CommitSegment segment = segment(intents, () -> true, intents::capacity);
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

    private static List<String> commitSegmentMatrix(List<String> failures, long tick) {
        List<String> lines = new ArrayList<>();
        IntentQueue queue = new IntentQueue(() -> 8, () -> 2);
        List<String> applied = new ArrayList<>();
        queue.bindPayload(intent -> {
            applied.add(intent.payloadHandle());
            return IntentPayload.Outcome.APPLIED;
        });
        queue.enqueue(intent(1L, "first"));
        queue.enqueue(intent(2L, "second"));
        CommitSegment holding = segment(queue, () -> false, queue::capacity);

        CommitSegment.Pass held = holding.run(tick);
        int heldDepth = queue.depth();
        long heldExecuted = queue.executedCount();
        lines.add("selftest.intent_hold_ran=" + (held.ran() ? 1 : 0));
        lines.add("selftest.intent_hold_depth=" + heldDepth);
        lines.add("selftest.intent_hold_mode=" + holding.mode());
        lines.add("selftest.intent_hold_executed=" + heldExecuted);

        CommitSegment walking = segment(queue, () -> true, queue::capacity);
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
        CommitOrder outOfOrder = queue.commit("world", 7L, tick);
        if (outOfOrder.code() != RejectCode.COMMIT_ORDER_VIOLATION || queue.orderViolationCount() != 1L) {
            failures.add("an order the channel never froze was not refused");
        }
        return lines;
    }

    private static WriteIntent intent(long id, String handle) {
        return WriteIntent.draft(id, "world", "world", "block_write", 0L,
            WriteIntent.UNTRACKED_EPOCH, handle, "xdomain", "site:a");
    }

    private static List<String> retryAndLifecycleMatrix(List<String> failures, long tick) {
        List<String> lines = new ArrayList<>();
        IntentPayloadDirectory payloads = new IntentPayloadDirectory();
        String failing = payloads.bind("block_write", () -> false);
        String landing = payloads.bind("block_write", () -> true);
        String landingElsewhere = payloads.bind("block_write", () -> true);
        IntentQueue queue = new IntentQueue(() -> 8, () -> 1);
        queue.bindPayload(payloads);
        CommitSegment segment = segment(queue, () -> true, queue::capacity);
        queue.enqueue(WriteIntent.draft(1L, "world-a", "world-a", "block_write", 0L,
            WriteIntent.UNTRACKED_EPOCH, failing, "xdomain", "site:a"));
        queue.enqueue(WriteIntent.draft(2L, "world-a", "world-a", "block_write", 0L,
            WriteIntent.UNTRACKED_EPOCH, landing, "xdomain", "site:a"));
        queue.enqueue(WriteIntent.draft(3L, "world-b", "world-b", "block_write", 0L,
            WriteIntent.UNTRACKED_EPOCH, landingElsewhere, "xdomain", "site:a"));

        CommitSegment.Pass first = segment.run(tick);
        int depthAfterFirst = queue.depth("world-a");
        CommitSegment.Pass second = segment.run(tick + 1L);
        lines.add("selftest.retry_budget=" + queue.retryBudget());
        lines.add("selftest.retry_first_code=" + (first.code() == null ? "none" : first.code().text()));
        lines.add("selftest.retry_second_code=" + (second.code() == null ? "none" : second.code().text()));
        lines.add("selftest.retry_first_steps=" + first.steps());
        lines.add("selftest.retry_first_depth=" + depthAfterFirst);
        lines.add("selftest.retry_exhausted=" + queue.retryExhaustedCount());
        lines.add("selftest.retry_released=" + queue.releasedCount());
        lines.add("selftest.retry_pending_payload=" + payloads.pendingCount());
        lines.add("selftest.shard_worlds=" + queue.shardCount());
        lines.add("selftest.shard_depth_a=" + queue.depth("world-a"));
        lines.add("selftest.shard_depth_b=" + queue.depth("world-b"));
        lines.add("selftest.shard_applied=" + payloads.appliedCount());
        lines.add("selftest.shard_abandoned=" + payloads.abandonedCount());

        if (first.code() != RejectCode.VERSION_MISMATCH || first.steps() != 0
            || depthAfterFirst != 2) {
            failures.add("a retryable refusal was not left in place for a second attempt"
                + " (code=" + first.code() + " steps=" + first.steps()
                + " depth=" + depthAfterFirst + ")");
        }
        if (second.code() != RejectCode.VERSION_MISMATCH || segment.released() != 1L
            || queue.retryExhaustedCount() != 1L) {
            failures.add("a payload past its retry budget was not released with its code");
        }
        if (queue.depth("world-a") != 0 || queue.depth("world-b") != 0) {
            failures.add("a failing write held the shard behind it");
        }
        if (payloads.appliedCount() != 2L || payloads.abandonedCount() != 1L) {
            failures.add("the shard did not apply the intents behind the released one");
        }

        WritePathCounters counters = new WritePathCounters();
        WriteLedger ledger = new WriteLedger();
        OwnerRegistry owners = new OwnerRegistry();
        IntentPayloadDirectory handovers = new IntentPayloadDirectory();
        IntentQueue epochs = new IntentQueue(() -> 8, () -> 2);
        WriteAuthority authority = new WriteAuthority(owners, epochs, ledger, () -> false, () -> 2);
        WorldWriteGuard guard = new WorldWriteGuard(counters, authority, epochs, handovers, ledger);
        epochs.bindPayload(guard);
        CommitSegment owned = segment(epochs, () -> true, epochs::capacity);
        guard.bindServerThread(Thread.currentThread(), "host:server-thread");
        guard.noteLiveWorlds(List.of("world-a", "world-b"));
        guard.refresh(true, false, true, tick);
        WorkerWrite handedOver = new WorkerWrite(guard, new Object(), "world-a");
        runOnWorker(handedOver);
        guard.noteLiveWorlds(List.of("world-b"));
        CommitSegment.Pass unloaded = owned.run(tick);
        lines.add("selftest.world_epoch_refusal="
            + (unloaded.code() == null ? "none" : unloaded.code().text()));
        lines.add("selftest.world_epoch_stale=" + guard.staleWorldRefusals());
        lines.add("selftest.world_epoch_abandoned=" + handovers.abandonedCount());
        lines.add("selftest.world_epoch_depth=" + epochs.depth("world-a"));
        if (unloaded.code() != RejectCode.WORLD_LIFECYCLE_DENIED
            || guard.staleWorldRefusals() != 1L) {
            failures.add("a write whose world was unloaded was not refused with its code");
        }

        IntentQueue foreignQueue = new IntentQueue(() -> 8, () -> 2);
        foreignQueue.bindPayload(intent -> IntentPayload.Outcome.APPLIED);
        foreignQueue.enqueue(WriteIntent.draft(9L, "world-a", "world-a", "block_write", 0L,
            WriteIntent.UNTRACKED_EPOCH, "handle", "xdomain", "site:a"));
        CommitSegment binding = new CommitSegment(foreignQueue, () -> true, foreignQueue::capacity);
        binding.bindOwnerThread(Thread.currentThread());
        CommitSegment.Pass[] foreign = new CommitSegment.Pass[1];
        Thread worker = new Thread(() -> foreign[0] = binding.run(tick), "worker-self-check");
        worker.start();
        join(worker);
        lines.add("selftest.commit_foreign_refused="
            + (foreign[0] == null || foreign[0].code() == null ? "none" : foreign[0].code().text()));
        lines.add("selftest.commit_foreign_runs=" + binding.foreignRuns());
        lines.add("selftest.commit_foreign_depth=" + foreignQueue.depth("world-a"));
        if (foreign[0] == null || foreign[0].code() != RejectCode.WRITE_DENIED_NOT_OWNER
            || binding.foreignRuns() != 1L || foreignQueue.depth("world-a") != 1) {
            failures.add("a commit asked for from a foreign thread was not refused");
        }
        safetyMatrix(lines, failures);
        exitsMatrix(lines, failures);
        feedbackMatrix(lines, failures);
        return lines;
    }

    /** Drives the five kinds of the safety net on both dimensions, the cascade cap, the escalation
     * switch, the empty-evidence refusal and the zero-effect criterion with one positive and three
     * counter-examples. */
    private static void safetyMatrix(List<String> lines, List<String> failures) {
        Map<SafetyNet.Point, Long> denied = new LinkedHashMap<>();
        denied.put(new SafetyNet.Point("world-a", "block_write|main|registered"), 2L);
        denied.put(new SafetyNet.Point("world-b", "platform_write|worker|unregistered"), 1L);
        Map<SafetyNet.Point, Long> waits = new LinkedHashMap<>();
        waits.put(new SafetyNet.Point(SafetyNet.NO_WORLD_SITE, "wait:chunk"), 1L);
        SafetyNet net = new SafetyNet(() -> false, () -> 3);
        List<SafetyNet.ViolationReport> reports = net.review(new SafetyNet.TickSources(denied, Map.of(), waits,
            1L, 1L, 0L, 1L), 10L);
        lines.add("selftest.safety_reports=" + reports.size() + " total=" + net.total());
        StringBuilder kinds = new StringBuilder();
        for (SafetyNet.KindCounts counts : net.kinds()) {
            if (kinds.length() > 0) {
                kinds.append(",");
            }
            kinds.append(counts.kind()).append("=").append(counts.total());
        }
        lines.add("selftest.safety_by_kind=" + kinds);
        lines.add("selftest.safety_sites=" + net.sites(SafetyNet.ViolationKind.CROSS_OWNER_WRITE).size()
            + " first=" + net.countAt(SafetyNet.ViolationKind.CROSS_OWNER_WRITE,
                "block_write|main|registered"));
        lines.add("selftest.safety_worlds=" + net.worlds(SafetyNet.ViolationKind.CROSS_OWNER_WRITE).size()
            + " world_a=" + net.countIn(SafetyNet.ViolationKind.CROSS_OWNER_WRITE, "world-a")
            + " world_b=" + net.countIn(SafetyNet.ViolationKind.CROSS_OWNER_WRITE, "world-b"));
        if (net.total() != 7L || net.kinds().size() != SafetyNet.ViolationKind.kindCount()
            || net.count(SafetyNet.ViolationKind.CROSS_OWNER_WRITE) != 3L
            || net.sites(SafetyNet.ViolationKind.CROSS_OWNER_WRITE).size() != 2
            || net.countIn(SafetyNet.ViolationKind.CROSS_OWNER_WRITE, "world-b") != 1L
            || net.count(SafetyNet.ViolationKind.HARD_TIMEOUT) != 1L
            || net.count(SafetyNet.ViolationKind.UNKNOWN_ACCESS) != 1L
            || net.count(SafetyNet.ViolationKind.VERSION_CONFLICT) != 1L
            || net.count(SafetyNet.ViolationKind.ESCALATE_SERIAL) != 1L) {
            failures.add("the safety net did not count the five kinds on both dimensions");
        }
        int zeroRows = 0;
        boolean paired = true;
        for (SafetyNet.ViolationKind kind : SafetyNet.ViolationKind.values()) {
            for (SafetyNet.Cell cell : net.readSite(kind)) {
                zeroRows = zeroRows + (cell.count() == 0L ? 1 : 0);
            }
            for (SafetyNet.Cell cell : net.readWorld(kind)) {
                zeroRows = zeroRows + (cell.count() == 0L ? 1 : 0);
            }
            paired = paired && net.siteBound(kind) == net.count(kind)
                && net.worldBound(kind) == net.count(kind);
        }
        lines.add("selftest.safety_dual_rows=" + net.readSite(SafetyNet.ViolationKind.UNKNOWN_ACCESS).size()
            + "/" + net.readWorld(SafetyNet.ViolationKind.UNKNOWN_ACCESS).size()
            + " zero_rows=" + zeroRows + " conservation=" + (paired ? 1 : 0));
        if (!paired || !net.conservationHolds() || zeroRows == 0) {
            failures.add("the two dimensions of a kind did not recompute into its one dimensional row");
        }
        for (int index = 0; index < 3; index++) {
            net.report(SafetyNet.ViolationKind.VERSION_CONFLICT, "world-a", "site:cascade", 11L + index, "one",
                "counted");
        }
        SafetyNet.Cascade cascade = net.cascade();
        lines.add("selftest.safety_cascade=" + cascade.cap() + "/" + cascade.depth() + " steps="
            + cascade.steps() + " capped=" + cascade.capped() + " stopped=" + cascade.stopped());
        if (cascade.capped() == 0L || cascade.stopped() == 0L) {
            failures.add("the cascade cap did not stop a repeated violation");
        }
        long before = net.total();
        net.report(SafetyNet.ViolationKind.CROSS_OWNER_WRITE, "world-a", "site:a", 14L, "", "counted");
        lines.add("selftest.safety_evidence_empty=" + net.evidenceEmpty() + " total_held="
            + (net.total() == before ? 1 : 0));
        if (net.total() != before || net.evidenceEmpty() != 1L) {
            failures.add("a violation without evidence was stored instead of refused");
        }
        SafetyNet switched = new SafetyNet(() -> true, () -> 8);
        switched.report(SafetyNet.ViolationKind.VERSION_CONFLICT, "world-a", "site:a", 1L, "one", "counted");
        lines.add("selftest.safety_escalation=" + net.escalated() + "/" + switched.escalated());
        if (net.escalated() != 0L || switched.escalated() != 1L) {
            failures.add("the escalation switch did not stay off, or did not count when on");
        }
        ZeroEffectDetector detector = new ZeroEffectDetector();
        detector.watch("b1", 10L, 5.0);
        ZeroEffectDetector.State still = detector.evaluate("b1", 15L, 5.0, 1L, 1L, 5).state();
        detector.watch("b2", 10L, 5.0);
        ZeroEffectDetector.State moved = detector.evaluate("b2", 15L, 9.0, 1L, 1L, 5).state();
        detector.watch("b3", 10L, 5.0);
        ZeroEffectDetector.State unproven = detector.evaluate("b3", 15L, 5.0, 1L, 0L, 5).state();
        detector.watch("b4", 10L, 5.0);
        ZeroEffectDetector.State pending = detector.evaluate("b4", 12L, 5.0, 1L, 1L, 5).state();
        lines.add("selftest.safety_zero_effect=" + still + "/" + moved + "/" + unproven + "/"
            + pending + " detected=" + detector.zeroEffectTotal() + " changed="
            + detector.changedTotal() + " unproven=" + detector.unprovenTotal());
        if (still != ZeroEffectDetector.State.ZERO_EFFECT
            || moved != ZeroEffectDetector.State.CHANGED
            || unproven != ZeroEffectDetector.State.UNPROVEN
            || pending != ZeroEffectDetector.State.PENDING
            || detector.zeroEffectTotal() != 1L) {
            failures.add("the zero-effect criterion did not separate its four outcomes");
        }
    }

    /** Drives the two exits over one window: the shared origins, the tail comparison, the refusal of
     * a window statistic on the control side, the refusal to serve a write path and the missing
     * counter that is named instead of zeroed. */
    private static void exitsMatrix(List<String> lines, List<String> failures) {
        DualExits exits = new DualExits(3);
        for (int index = 1; index <= 3; index++) {
            exits.noteTick(new DualExits.Sample(index, 1.0 * index, index, 0L, 0.5, 4.0, "normal"),
                List.of());
        }
        DualExits.SameSource same = exits.sameSource();
        lines.add("selftest.exit_frames=" + exits.controlFrames() + "/" + exits.judgementFrames()
            + " window=" + exits.judgement().windowTicks() + " tick=" + exits.judgement().tickIndex());
        lines.add("selftest.exit_same_source=" + same.sameOrigin() + "/" + same.sameValue() + " of "
            + same.fields() + " equal=" + (same.equal() ? 1 : 0) + " at=" + same.controlTick() + "/"
            + same.judgementTick());
        lines.add("selftest.exit_groups=" + exits.groups());
        DualExits other = new DualExits(3);
        for (int index = 1; index <= 3; index++) {
            other.noteTick(new DualExits.Sample(index, 9.0, index + 7, 0L, 0.5, 1.0, "normal"),
                List.of());
        }
        DualExits.SameSource mismatch = DualExits.compare(exits.control(), other.judgement());
        lines.add("selftest.exit_same_source_negative=" + mismatch.sameValue() + " of "
            + mismatch.fields() + " equal=" + (mismatch.equal() ? 1 : 0));
        DualExits partial = new DualExits(1);
        List<String> absent = List.of("reserve_remaining_ms");
        DualExits.Frame cut = partial.noteTick(
            new DualExits.Sample(1L, 0.0, 0L, 0L, 0.0, 0.0, "none"), absent);
        lines.add("selftest.exit_missing=" + (cut.complete() ? 0 : 1) + " named="
            + cut.missing().size() + " value=" + cut.reading("reserve_remaining_ms").text()
            + " rows=" + cut.readings().size());
        lines.add("selftest.exit_guards=" + exits.offerWindowStatistic("overrun_hits.window_sum", 1.0)
            + "/" + exits.noteWriteDependency(DualExits.Plane.JUDGEMENT) + " feeds="
            + exits.controlWindowFeeds() + " deps=" + exits.judgementWriteDependencies());
        if (!same.equal() || same.fields() != DualExits.controlNames().size()
            || mismatch.equal() || !cut.missing().contains("reserve_remaining_ms")
            || cut.complete() || cut.readings().size() != 6
            || exits.controlWindowFeeds() != 1L || exits.judgementWriteDependencies() != 1L) {
            failures.add("the two exits crossed, or a missing counter was published as a zero");
        }
    }

    /** Drives the closed loop of the planning period: the frame is taken on one tick, consumed by
     * the next plan, and a frame taken over a window is refused. */
    private static void feedbackMatrix(List<String> lines, List<String> failures) {
        TickPlanPlanner.Input base = new TickPlanPlanner.Input(10L, 2L, 1L, List.of("world-a"),
            List.of("entity"), TickPlanStore.Control.of(9L, 1L, 1L, 1.0, 0L, 0L, 4.0, "normal"),
            declarationsForFeedback(), new SharePlanner().plan(List.of("world-a"), 10L, Map.of()),
            64, TickPlanStore.Feedback.tick(9L, 1L, 1L, 0L, 3L, 5L, 0.25));
        TickPlanPlanner.Result carried = TickPlanPlanner.plan(base);
        TickPlanPlanner.Result refused = TickPlanPlanner.plan(new TickPlanPlanner.Input(10L, 2L, 1L,
            List.of("world-a"), List.of("entity"),
            TickPlanStore.Control.of(9L, 1L, 1L, 1.0, 0L, 0L, 4.0, "normal"),
            declarationsForFeedback(), new SharePlanner().plan(List.of("world-a"), 10L, Map.of()),
            64, TickPlanStore.Feedback.overWindow(9L, 600L, 0.25)));
        TickPlan.DomainMode mode = carried.ok() ? carried.plan().modeOf("world-a", "entity") : null;
        lines.add("selftest.feedback_consumed=" + (carried.ok() ? 1 : 0) + " mode="
            + (mode == null ? "none" : mode.mode() + "/" + mode.reason()) + " rate="
            + (carried.ok() ? carried.plan().feedback().failureRate() : -1.0) + " carried="
            + (carried.ok() ? carried.plan().tickIndex() - carried.plan().feedback().tickIndex()
                : -1L));
        TickPlanPlanner.Result carriedUnknown = TickPlanPlanner.plan(new TickPlanPlanner.Input(10L,
            2L, 1L, List.of("world-a"), List.of("entity"),
            TickPlanStore.Control.of(9L, 1L, 1L, 1.0, 0L, 0L, 4.0, "normal"),
            declarationsForFeedback(), new SharePlanner().plan(List.of("world-a"), 10L, Map.of()),
            64, TickPlanStore.Feedback.tick(9L, 1L, 0L, 2L, 0L, 2L, 0.0)));
        TickPlan.DomainMode unknownMode = carriedUnknown.ok()
            ? carriedUnknown.plan().modeOf("world-a", "entity") : null;
        lines.add("selftest.feedback_unknown_carried="
            + (unknownMode == null ? "none" : unknownMode.mode() + "/" + unknownMode.reason()));
        lines.add("selftest.feedback_window_refused="
            + (refused.code() == null ? "none" : refused.code().text()));
        TickPlanStore store = new TickPlanStore(4);
        store.noteControl(TickPlanStore.Control.of(9L, 1L, 1L, 1.0, 0L, 0L, 4.0, "normal"));
        store.noteFailure(RejectCode.DAG_CYCLE);
        store.takeFeedback(9L, 1L);
        TickPlanStore.Feedback taken = store.feedback();
        lines.add("selftest.feedback_taken=" + taken.scope() + "/" + taken.tickIndex() + " tick_failures="
            + taken.tickFailures() + " rate=" + taken.failureRate());
        // The one hop the loop has to make: the frame the store took is the frame the planner of
        // the next tick consumes, and the values it carries are the values it was read with.
        TickPlanPlanner.Result closedLoop = TickPlanPlanner.plan(new TickPlanPlanner.Input(10L, 2L,
            1L, List.of("world-a"), List.of("entity"),
            TickPlanStore.Control.of(9L, 1L, 1L, 1.0, 0L, 0L, 4.0, "normal"),
            declarationsForFeedback(), new SharePlanner().plan(List.of("world-a"), 10L, Map.of()),
            64, taken));
        TickPlan.DomainMode loopMode = closedLoop.ok()
            ? closedLoop.plan().modeOf("world-a", "entity") : null;
        boolean loopLinked = closedLoop.ok()
            && closedLoop.plan().feedback().failureRate() == taken.failureRate()
            && closedLoop.plan().feedback().tickIndex() == taken.tickIndex()
            && closedLoop.plan().feedback().tickFailures() == taken.tickFailures();
        lines.add("selftest.feedback_loop=" + (loopMode == null ? "none"
            : loopMode.mode() + "/" + loopMode.reason()) + " linked=" + (loopLinked ? 1 : 0)
            + " rate=" + taken.failureRate() + " fired=" + taken.tickFailures());
        if (!loopLinked || loopMode == null || loopMode.reason() != TickPlan.Reason.DEGRADED) {
            failures.add("the frame the store took did not reach the next plan unchanged");
        }
        if (!carried.ok() || mode == null || mode.mode() != TickPlan.Mode.CONSERVATIVE
            || mode.reason() != TickPlan.Reason.DEGRADED
            || carried.plan().feedback().failureRate() != 0.25
            || refused.code() != RejectCode.COUNTER_MISSING
            || !taken.tickScoped() || taken.tickFailures() != 1L || taken.failureRate() <= 0.0) {
            failures.add("the plan did not consume the tick-scoped frame, or consumed a window one");
        }
    }

    private static List<JobDeclaration> declarationsForFeedback() {
        JobDeclaration.DomainRef ref = new JobDeclaration.DomainRef("world-a", "entity", 0);
        return List.of(new JobDeclaration("a/0", 1L, "world-a", "entity", 0, List.of(), 0, "a",
            "region", List.of(ref), List.of(ref), ShareClass.ENTITY,
            JobDeclaration.SiteClass.PARALLEL, 0, 4));
    }

    private static void join(Thread thread) {
        try {
            thread.join();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static CommitSegment segment(IntentQueue queue, BooleanSupplier enabled,
                                         IntSupplier budget) {
        CommitSegment segment = new CommitSegment(queue, enabled, budget);
        segment.bindOwnerThread(Thread.currentThread());
        return segment;
    }

    private static List<String> waitSiteMatrix(List<String> failures, long tick) {
        List<String> lines = new ArrayList<>();
        WaitPointRegistry registry = new WaitPointRegistry(() -> 50);
        WaitSiteObserver observer = new WaitSiteObserver(registry, () -> tick, () -> 50);

        // The seam is borrowed, not taken over: the watcher that was installed before the check is
        // read first and handed back in every path out of here, and the module that owns the
        // production watcher is told to look at the seam again. Without this the check would leave the
        // twenty real call sites reporting into nothing while the module still believed its own
        // watcher was installed.
        PrtsWaitSites.SiteWaitTap previous = PrtsWaitSites.watcher();
        PrtsWaitSites.install(observer);
        try {
            PrtsWaitSites.begin(PrtsWaitSites.SERVER_LEVEL_SET_CHUNK_FORCED);
            PrtsWaitSites.end(PrtsWaitSites.SERVER_LEVEL_SET_CHUNK_FORCED);
        } finally {
            PrtsWaitSites.install(previous);
            KernelModule.instance().resyncWaitSiteTap();
        }
        observer.observed(PrtsWaitSites.ENTITY_SET_POS_RAW,
            PrtsWaitSites.SITE_IDS[PrtsWaitSites.ENTITY_SET_POS_RAW], "world", 60_000_000L);
        observer.observed(PrtsWaitSites.BLOCK_COLLISIONS_COMPUTE_NEXT,
            PrtsWaitSites.SITE_IDS[PrtsWaitSites.BLOCK_COLLISIONS_COMPUTE_NEXT], "world",
            50_000_000L);
        observer.observed(PrtsWaitSites.NATURAL_SPAWNER_SPAWN_CATEGORY,
            PrtsWaitSites.SITE_IDS[PrtsWaitSites.NATURAL_SPAWNER_SPAWN_CATEGORY], "world",
            10_000_000L);

        int zeroRows = 0;
        for (int index = 0; index < observer.readings().siteCount(); index++) {
            if (observer.readings().observed(index) == 0L) {
                zeroRows++;
            }
        }
        lines.add("selftest.wait_site_seam_hits="
            + observer.readings().observed(PrtsWaitSites.SERVER_LEVEL_SET_CHUNK_FORCED));
        lines.add("selftest.wait_site_observed=" + observer.readings().observedTotal());
        lines.add("selftest.wait_site_registry_observed=" + registry.observationCount());
        lines.add("selftest.wait_site_over_one_tick=" + observer.readings().overOneTickTotal());
        lines.add("selftest.wait_site_candidates="
            + observer.readings().convergenceCandidateTotal());
        lines.add("selftest.wait_site_max_ms=" + observer.readings().maxMs());
        lines.add("selftest.wait_site_zero_rows=" + zeroRows);
        lines.add("selftest.wait_site_registered_total=" + registry.reportCoverage().siteInventoryTotal());
        lines.add("selftest.wait_site_unregistered=" + registry.reportCoverage().siteUnregistered());

        if (observer.readings().observed(PrtsWaitSites.SERVER_LEVEL_SET_CHUNK_FORCED) != 1L) {
            failures.add("a wait driven through the seam never reached the observer");
        }
        if (observer.readings().observedTotal() != 4L
            || registry.observationCount() != 4L) {
            failures.add("the wait observations were not recorded once each");
        }
        if (observer.readings().overOneTickTotal() != 1L) {
            failures.add("a wait longer than a host tick was not counted exactly once");
        }
        if (observer.readings().convergenceCandidateTotal() != 1L) {
            failures.add("a wait over the upper bound was not counted exactly once");
        }
        if (observer.readings().maxMs() != 60L) {
            failures.add("the longest observed wait was not published");
        }
        if (zeroRows != observer.readings().siteCount() - 4) {
            failures.add("a call site that never waited was not published as zero");
        }
        if (registry.reportCoverage().siteUnregistered() != 0) {
            failures.add("an observed call site was not resolved to its row");
        }
        lines.add("selftest.wait_watcher_restored=" + (PrtsWaitSites.watcher() == previous ? 1 : 0));
        if (PrtsWaitSites.watcher() != previous) {
            failures.add("the wait observation seam was not handed back after the check");
        }
        return lines;
    }

    /** The dependency-contract side of the wait registry: the nine rows it must serve, the four
     * items one observation carries, the progress reading, the refusal interface and the bound gate.
     * Every check builds its own registry, so the live counters are never touched. */
    private static List<String> waitContractMatrix(List<String> failures, long tick) {
        List<String> lines = new ArrayList<>();
        WaitPointRegistry waits = new WaitPointRegistry(() -> 50);
        WaitPointRegistry.NineRows nine = waits.nineRows();
        WaitPointRegistry.WaitPointEntry alias = waits.lookup("chunk");
        lines.add("selftest.wait_nine_rows=" + nine.rows());
        lines.add("selftest.wait_nine_contract=" + nine.contractRows());
        lines.add("selftest.wait_nine_aligned=" + (nine.aligned() ? 1 : 0));
        lines.add("selftest.wait_nine_appended=" + nine.appended().size());
        lines.add("selftest.wait_row_key=" + (alias == null ? "none" : alias.wpId()));
        if (!nine.aligned() || nine.rows() != 9 || nine.contractRows() != 9) {
            failures.add("the registry does not serve exactly the nine contract rows");
        }
        if (alias == null) {
            failures.add("a contract row is not reachable by its key");
        }

        long[] depth = {0L};
        WaitProgress progress = waits.progress();
        progress.bind("xdomain", "intent.queue_depth", () -> depth[0]);
        WaitProgress.Reading still = progress.read("xdomain");
        depth[0] = 3L;
        WaitProgress.Reading moved = progress.read("xdomain");
        WaitProgress.Reading unbound = progress.read("xworld");
        lines.add("selftest.wait_signal_bound=" + progress.boundCount() + "/"
            + progress.declaredCount());
        lines.add("selftest.wait_signal_still=" + still.value() + "/" + still.delta());
        lines.add("selftest.wait_signal_moved=" + moved.value() + "/" + moved.delta()
            + "/" + (moved.advancing() ? 1 : 0));
        lines.add("selftest.wait_signal_unbound=" + unbound.value() + "/"
            + (unbound.bound() ? 1 : 0) + "/" + unbound.source());
        if (still.delta() != 0L || moved.delta() != 3L || !moved.advancing()) {
            failures.add("a bound progress signal did not publish its movement");
        }
        if (unbound.bound() || unbound.value() != 0L) {
            failures.add("a row whose producer is missing did not publish an unbound zero reading");
        }

        WaitPointRegistry counting = new WaitPointRegistry(() -> 50);
        WaitObservation counted = counting.observeWait(null, new WaitSpan(null, "unknown.call",
            "site:a", "world", tick, 10L, null));
        WaitPointRegistry refusing = new WaitPointRegistry(() -> 50, () -> true);
        WaitObservation refused = refusing.observeWait(null, new WaitSpan(null, "unknown.call",
            "site:a", "world", tick, 10L, null));
        lines.add("selftest.wait_counted_rejection="
            + (counted.rejection() == null ? "none" : counted.rejection()));
        lines.add("selftest.wait_counted_refusals=" + counting.refusedUnregistered());
        lines.add("selftest.wait_refused_code="
            + (refused.rejection() == null ? "none" : refused.rejection()));
        lines.add("selftest.wait_refused_count=" + refusing.refusedUnregistered());
        lines.add("selftest.wait_refused_still_counted=" + refusing.unregisteredCallSites());
        if (counted.refused() || counting.refusedUnregistered() != 0L) {
            failures.add("a wait was refused while the refusal switch was off");
        }
        if (!refused.refused() || !RejectCode.PROGRESS_UNOBSERVED.text().equals(refused.rejection())) {
            failures.add("an unregistered wait was not answered with its refusal code");
        }
        if (refusing.unregisteredCallSites() != 1 || refusing.observationCount() != 1L) {
            failures.add("the refusal replaced the count instead of joining it");
        }

        WaitPointRegistry bounded = new WaitPointRegistry(() -> 50);
        WaitObservation crossed = bounded.observeWait("chunk", new WaitSpan("chunk",
            "chunk.materialize", "site:a", "world", tick, 80L, null));
        bounded.noteTick();
        bounded.noteTick();
        ForcedConvergence.Rollback early = bounded.convergence()
            .rollback(ForcedConvergence.ROLLBACK_WINDOW_TICKS);
        bounded.noteTick();
        bounded.noteTick();
        ForcedConvergence.Rollback ready = bounded.convergence()
            .rollback(ForcedConvergence.ROLLBACK_WINDOW_TICKS);
        lines.add("selftest.wait_rollback_window=" + ForcedConvergence.ROLLBACK_WINDOW_TICKS);
        lines.add("selftest.wait_row_producer=" + crossed.producer());
        lines.add("selftest.wait_row_signal=" + crossed.signal().kind() + ":"
            + crossed.signal().fieldRef());
        lines.add("selftest.wait_row_action=" + crossed.timeoutAction());
        lines.add("selftest.wait_row_degrade=" + crossed.degradeTo());
        lines.add("selftest.wait_reached=" + bounded.convergence().reached());
        lines.add("selftest.wait_effective=" + bounded.convergence().effective());
        lines.add("selftest.wait_overrun_by_row=" + bounded.overrunOf("chunk"));
        lines.add("selftest.wait_rollback_early=" + (early.ready() ? 1 : 0));
        lines.add("selftest.wait_rollback_ready=" + (ready.ready() ? 1 : 0));
        if (!crossed.complete() || crossed.producer() == null || crossed.signal() == null
            || crossed.timeoutAction() == null || crossed.degradeTo() == null) {
            failures.add("an observation did not carry the four contract items");
        }
        if (!crossed.wouldConverge() || bounded.convergence().reached() != 1L) {
            failures.add("a wait over the bound did not reach the action its row declares");
        }
        if (bounded.convergence().effective() != 0L || bounded.forcedConvergence() != 0L) {
            failures.add("a forced convergence action was reported as executed");
        }
        if (early.ready() || !ready.ready()) {
            failures.add("the rollback gate did not wait for its window of clean ticks");
        }
        if (bounded.overrunOf("chunk") != 1L || bounded.waitOverrunCount() != 1L) {
            failures.add("a wait over the bound was not counted against its row");
        }
        return lines;
    }


    /** The budget side: the conservation equation with all three of its terms, the two states and
     * their conditions, the per-class metering and the five rungs of the resource ladder. Every
     * fixture builds its own objects, so the live counters are never touched. */
    private static List<String> budgetGovernanceMatrix(List<String> failures, long tick) {
        List<String> lines = new ArrayList<>();
        SharePlanner planner = new SharePlanner();
        ShareTable table = planner.plan(List.of("world-a", "world-b"), tick, Map.of());
        ConservationCheck planned = planner.checkConservation(table);
        ConservationCheck critical = planner.checkConservation(
            squeeze(table, planned.plannedMs() + 0.5));
        ConservationCheck over = planner.checkConservation(squeeze(table, planned.plannedMs() - 1.0));
        ConservationCheck unplanned = planner.checkConservation(null);
        lines.add("selftest.budget_terms=" + fmt(planned.sumSharesMs()) + "/"
            + fmt(planned.reserveMs()) + "/" + fmt(planned.hostOverheadMs()));
        lines.add("selftest.budget_planned=" + fmt(planned.plannedMs()) + "/"
            + fmt(planned.eBudgetMs()) + "/" + fmt(planned.slackMs()));
        lines.add("selftest.budget_verdicts=" + planned.verdict() + "/" + critical.verdict() + "/"
            + over.verdict() + "/" + unplanned.planned());
        lines.add("selftest.budget_over_ms=" + fmt(over.overByMs()));
        if (planned.verdict() != ConservationCheck.Verdict.OK
            || critical.verdict() != ConservationCheck.Verdict.CRITICAL
            || over.verdict() != ConservationCheck.Verdict.OVER || unplanned.planned()) {
            failures.add("the conservation equation did not publish its three bands");
        }
        if (Math.abs(planned.plannedMs()
            - (planned.sumSharesMs() + planned.reserveMs() + planned.hostOverheadMs())) > 1.0e-9) {
            failures.add("the planned total is not the sum of its three terms");
        }

        BudgetStateMachine machine = new BudgetStateMachine();
        BudgetStateMachine.Decision normal = machine.judge(tick, table, 0L, 0L, planned);
        BudgetStateMachine.Decision overrun = machine.judge(tick + 1L, table, 1L, 0L, planned);
        BudgetStateMachine.Decision waitHit = machine.judge(tick + 2L, table, 0L, 1L, planned);
        BudgetStateMachine.Decision broken = machine.judge(tick + 3L, table, 0L, 0L, over);
        BudgetStateMachine.Decision drawn = machine.judge(tick + 4L, drawnReserve(table), 0L, 0L,
            planned);
        BudgetStateMachine.Decision back = machine.judge(tick + 5L, table, 0L, 0L, planned);
        lines.add("selftest.budget_phase=" + normal.phase() + "/" + overrun.phase() + "/"
            + waitHit.phase() + "/" + broken.phase() + "/" + drawn.phase() + "/" + back.phase());
        lines.add("selftest.budget_reason=" + overrun.reason() + "|" + back.reason());
        lines.add("selftest.budget_transitions=" + back.enteredCount() + "/" + back.leftCount()
            + " entered_tick=" + overrun.enteredTick());
        if (normal.phase() != BudgetStateMachine.Phase.NORMAL
            || !overrun.degraded() || !waitHit.degraded() || !broken.degraded()
            || !drawn.degraded() || back.phase() != BudgetStateMachine.Phase.NORMAL) {
            failures.add("the two budget states did not follow their four conditions");
        }
        if (back.enteredCount() != 1L || back.leftCount() != 1L) {
            failures.add("the two budget states did not count their transitions");
        }
        if (overrun.enteredTick() != tick + 1L) {
            failures.add("the degraded state did not publish the tick it was entered on");
        }

        long[] totals = new long[SelfClass.values().length];
        totals[SelfClass.ENTITY.ordinal()] = 2_000_000L;
        totals[SelfClass.AI.ordinal()] = 500_000L;
        ShareMeter.TickReading reading = ShareMeter.read(tick, true, Map.of("world-a", totals));
        int zeroRows = 0;
        for (ShareMeter.ClassReading row : reading.rows()) {
            if (row.usedMs() == 0.0) {
                zeroRows++;
            }
        }
        lines.add("selftest.meter_rows=" + reading.rowCount() + "/" + ShareClass.rowCount()
            + " complete=" + (reading.complete() ? 1 : 0));
        lines.add("selftest.meter_entity_ms=" + fmt(reading.row(ShareClass.ENTITY).usedMs())
            + " ai_ms=" + fmt(reading.row(ShareClass.AI).usedMs()) + " zero_rows=" + zeroRows);
        lines.add("selftest.meter_unmapped=" + reading.unmapped().size());
        if (!reading.complete() || reading.rowCount() != ShareClass.rowCount()) {
            failures.add("the per-class metering does not carry one row per share class");
        }
        if (Math.abs(reading.row(ShareClass.ENTITY).usedMs() - 2.0) > 1.0e-9
            || Math.abs(reading.row(ShareClass.AI).usedMs() - 0.5) > 1.0e-9) {
            failures.add("the per-class metering did not convert the samples of its source rows");
        }
        if (reading.row(ShareClass.GRAPH) == null || reading.row(ShareClass.GRAPH).usedMs() != 0.0) {
            failures.add("a share class without samples did not publish a zero row");
        }
        if (reading.unmapped().size() != 3) {
            failures.add("the timer rows that feed no share class were not named");
        }

        DegradeLadder ladder = new DegradeLadder(() -> false);
        DegradeLadder.Advance advance = ladder.noteEntered(DegradeLevel.B3, tick);
        int completeRungs = 0;
        for (DegradeLadder.Rung rung : ladder.rungs()) {
            if (!rung.trigger().isBlank() && !rung.action().isBlank() && !rung.signal().isBlank()
                && !rung.returnCondition().isBlank() && rung.code() != null) {
                completeRungs++;
            }
        }
        lines.add("selftest.ladder_rungs=" + ladder.rungs().size() + "/" + completeRungs);
        lines.add("selftest.ladder_walked=" + advance.entered().size() + " skipped="
            + (advance.skipped() ? 1 : 0) + " deepest=" + advance.reached());
        lines.add("selftest.ladder_entered_b3=" + ladder.counters(DegradeLevel.B3).entered());
        lines.add("selftest.ladder_effective_off=" + (ladder.noteEffective(DegradeLevel.B3) ? 1 : 0)
            + " effective_total=" + ladder.effectiveTotal());
        lines.add("selftest.ladder_code_skip="
            + ladder.codeCount(RejectCode.TICK_BUDGET_EXHAUSTED));
        if (ladder.rungs().size() != 5 || completeRungs != 5) {
            failures.add("a rung of the resource ladder is missing one of its four items");
        }
        if (advance.entered().size() != 3 || !advance.skipped()
            || ladder.counters(DegradeLevel.B1).entered() != 1L
            || ladder.counters(DegradeLevel.B2).entered() != 1L
            || ladder.counters(DegradeLevel.B3).entered() != 1L) {
            failures.add("the resource ladder did not walk its order one rung at a time");
        }
        if (ladder.effectiveTotal() != 0L
            || ladder.codeCount(RejectCode.TICK_BUDGET_EXHAUSTED) == 0L) {
            failures.add("a rung that may not act reported an action, or a skip raised no code");
        }
        ladder.noteTick(false);
        ladder.noteTick(false);
        DegradeLadder.Gate early = ladder.gate(DegradeLevel.B3, 3);
        ladder.noteTick(false);
        DegradeLadder.Gate unbound = ladder.gate(DegradeLevel.B3, 3);
        ladder.bindSecondCondition(DegradeLevel.B3, "fixture.deferred_batches", () -> true);
        DegradeLadder.Gate ready = ladder.gate(DegradeLevel.B3, 3);
        lines.add("selftest.ladder_gate=" + early.blockedBy() + "/" + unbound.blockedBy() + "/"
            + ready.blockedBy() + "/" + (ready.ready() ? 1 : 0));
        if (early.ready() || unbound.ready() || !ready.ready()) {
            failures.add("the return gate did not keep a rung until both of its conditions held");
        }
        DegradeLadder acting = new DegradeLadder(() -> true);
        acting.noteEntered(DegradeLevel.B1, tick);
        boolean effective = acting.noteEffective(DegradeLevel.B1);
        boolean returned = acting.noteReturned(DegradeLevel.B1);
        lines.add("selftest.ladder_acting=" + (effective ? 1 : 0) + "/" + (returned ? 1 : 0)
            + " deepest=" + acting.sign().deepest() + " total=" + acting.effectiveTotal());
        if (!effective || !returned || acting.sign().deepest() != DegradeLevel.NONE) {
            failures.add("a ladder allowed to act did not count its action or its return");
        }
        ladder.noteTick(true);
        lines.add("selftest.ladder_ticks=" + ladder.sign().ticksObserved() + "/"
            + ladder.sign().cleanTicks());
        if (ladder.sign().cleanTicks() != 0L) {
            failures.add("a tick that carried an overrun did not end the clean run");
        }
        return lines;
    }

    /** The three contract layers, driven on their own objects: the graph freeze and its refusals, the
     * scheduler with its affinity, its backpressure, its cancellation and its gate, the planning
     * period with its refusals, its modes and its order, and the commit log with its order invariant,
     * its bounded rings and its replay comparison. */
    private static List<String> contractLayerMatrix(List<String> failures, long tick) {
        List<String> lines = new ArrayList<>();
        String world = "world-a";
        JobDeclaration.DomainRef ref = new JobDeclaration.DomainRef(world, "entity", 0);
        List<JobDeclaration> declarations = new ArrayList<>();
        declarations.add(declaration("a/0", world, "entity", "r0", 0, ShareClass.ENTITY,
            JobDeclaration.SiteClass.PARALLEL, List.of(), 1));
        declarations.add(declaration("a/1", world, "entity", "r1", 1, ShareClass.ENTITY,
            JobDeclaration.SiteClass.PARALLEL, List.of("a/0"), 1));
        declarations.add(declaration("a/2", world, "entity", "r2", 2, ShareClass.ENTITY,
            JobDeclaration.SiteClass.UNKNOWN, List.of("a/1"), 1));
        declarations.add(declaration("a/3", world, "entity", "r3", 3, ShareClass.ENTITY,
            JobDeclaration.SiteClass.PARALLEL, List.of("a/2", "a/0"), 1));
        JobGraphBuilder.Freeze first = JobGraphBuilder.freeze(declarations, tick, 1L, 1L, 64);
        List<JobDeclaration> shuffled = new ArrayList<>();
        for (int index = declarations.size() - 1; index >= 0; index--) {
            shuffled.add(declarations.get(index));
        }
        JobGraphBuilder.Freeze second = JobGraphBuilder.freeze(shuffled, tick, 1L, 1L, 64);
        String firstOrder = first.ok() ? joinIds(first.graph().order()) : "refused";
        String secondOrder = second.ok() ? joinIds(second.graph().order()) : "refused";
        lines.add("selftest.jobgraph_nodes=" + (first.ok() ? first.graph().nodeCount() : 0)
            + " edges=" + (first.ok() ? first.graph().edgeCount() : 0)
            + " roots=" + (first.ok() ? first.graph().ready().size() : 0)
            + " lanes=" + (first.ok() ? first.graph().affinity().size() : 0));
        lines.add("selftest.jobgraph_order_equal=" + (firstOrder.equals(secondOrder) ? 1 : 0)
            + " order=" + firstOrder);
        if (!first.ok() || !second.ok() || !firstOrder.equals(secondOrder)) {
            failures.add("two freezes of the same declarations did not produce the same order");
        }
        if (first.ok() && first.graph().order().size() != declarations.size()) {
            failures.add("the frozen graph does not order every declaration");
        }
        lines.add("selftest.jobgraph_split_intents=" + (first.ok() ? first.graph().splitIntents() : -1));

        List<JobDeclaration> cyclic = new ArrayList<>(declarations);
        cyclic.add(declaration("a/4", world, "entity", "r4", 4, ShareClass.ENTITY,
            JobDeclaration.SiteClass.PARALLEL, List.of("a/5"), 1));
        cyclic.add(declaration("a/5", world, "entity", "r5", 5, ShareClass.ENTITY,
            JobDeclaration.SiteClass.PARALLEL, List.of("a/4"), 1));
        JobGraphBuilder.Freeze cycle = JobGraphBuilder.freeze(cyclic, tick, 1L, 1L, 64);
        JobGraphBuilder.Freeze duplicate = JobGraphBuilder.freeze(
            List.of(declaration("a/0", world, "entity", "r0", 0, ShareClass.ENTITY,
                JobDeclaration.SiteClass.PARALLEL, List.of(), 1),
                declaration("a/0", world, "entity", "r9", 0, ShareClass.ENTITY,
                    JobDeclaration.SiteClass.PARALLEL, List.of(), 1)), tick, 1L, 1L, 64);
        JobDeclaration crossWorld = new JobDeclaration("x/0", 1L, world, "entity", 0, List.of(), 0,
            "r0", "region", List.of(ref), List.of(new JobDeclaration.DomainRef("world-b", "entity", 0)),
            ShareClass.ENTITY, JobDeclaration.SiteClass.PARALLEL, 0, 4);
        JobGraphBuilder.Freeze cross = JobGraphBuilder.freeze(List.of(crossWorld), tick, 1L, 1L, 64);
        JobDeclaration unbounded = new JobDeclaration("u/0", 1L, world, "entity", 0, List.of(), 0,
            "r0", "region", List.of(ref), List.of(ref), ShareClass.ENTITY,
            JobDeclaration.SiteClass.PARALLEL, 0, 0);
        JobGraphBuilder.Freeze bound = JobGraphBuilder.freeze(List.of(unbounded), tick, 1L, 1L, 64);
        JobGraphBuilder.Freeze capped = JobGraphBuilder.freeze(declarations, tick, 1L, 1L, 2);
        JobGraphBuilder.Freeze unknownPredecessor = JobGraphBuilder.freeze(
            List.of(declaration("a/9", world, "entity", "r9", 0, ShareClass.ENTITY,
                JobDeclaration.SiteClass.PARALLEL, List.of("nobody"), 1)), tick, 1L, 1L, 64);
        lines.add("selftest.jobgraph_refusals=" + codeOf(cycle) + "/" + codeOf(duplicate) + "/"
            + codeOf(cross) + "/" + codeOf(bound) + "/" + codeOf(capped) + "/"
            + codeOf(unknownPredecessor));
        if (cycle.code() != RejectCode.DAG_CYCLE
            || duplicate.code() != RejectCode.WRITE_DENIED_NOT_OWNER
            || cross.code() != RejectCode.CROSS_WORLD_WRITE_DENIED
            || bound.code() != RejectCode.QUEUE_CAP_EXCEEDED
            || capped.code() != RejectCode.QUEUE_CAP_EXCEEDED
            || unknownPredecessor.code() != RejectCode.WRITE_DENIED_NOT_OWNER) {
            failures.add("a declaration the contract refuses was frozen anyway");
        }

        JobGraph graph = first.graph();
        JobScheduler scheduler = new JobScheduler();
        scheduler.begin(graph);
        List<Long> handedOut = new ArrayList<>();
        int emptyRounds = 0;
        while (true) {
            JobScheduler.Step step = scheduler.next();
            if (step == null) {
                emptyRounds++;
                if (emptyRounds > 4 || handedOut.size() >= graph.nodeCount()) {
                    break;
                }
                continue;
            }
            emptyRounds = 0;
            handedOut.add(step.nodeId());
            scheduler.settle(step.nodeId());
        }
        lines.add("selftest.scheduler_order=" + joinIds(handedOut) + " lanes=" + scheduler.lanes()
            + " dispatched=" + scheduler.dispatched() + " settled=" + scheduler.settledTotal());
        if (!joinIds(handedOut).equals(firstOrder) || !scheduler.closed()) {
            failures.add("the scheduler did not hand out the frozen order of the graph");
        }
        List<JobDeclaration> twoRoots = new ArrayList<>();
        twoRoots.add(declaration("b/0", world, "entity", "r0", 0, ShareClass.ENTITY,
            JobDeclaration.SiteClass.PARALLEL, List.of(), 1));
        twoRoots.add(declaration("b/1", world, "entity", "r1", 0, ShareClass.ENTITY,
            JobDeclaration.SiteClass.PARALLEL, List.of(), 1));
        twoRoots.add(declaration("b/2", world, "entity", "r2", 0, ShareClass.ENTITY,
            JobDeclaration.SiteClass.PARALLEL, List.of("b/0"), 1));
        JobGraph wide = JobGraphBuilder.freeze(twoRoots, tick, 1L, 1L, 64).graph();
        JobScheduler cappedScheduler = new JobScheduler();
        cappedScheduler.begin(wide, 1);
        boolean admitted = cappedScheduler.next() != null;
        boolean refusedAgain = cappedScheduler.next() == null
            && cappedScheduler.backpressureHits() > 0L;
        if (!admitted || !refusedAgain) {
            failures.add("the declared bound of jobs in flight did not refuse a second job");
        }
        lines.add("selftest.scheduler_backpressure=" + cappedScheduler.backpressureHits()
            + " peak=" + cappedScheduler.queuedPeak() + " cap=" + cappedScheduler.inFlightCap());

        JobScheduler cancelling = new JobScheduler();
        cancelling.begin(graph);
        JobScheduler.CancelReport report = cancelling.cancel(nodeIdOf(graph, "a/0"));
        lines.add("selftest.scheduler_cancel=" + report.cancelled().size() + " kept="
            + report.kept().size() + " scopes=" + report.scopes().size());
        if (report.cancelled().size() != graph.nodeCount() || report.code() != null) {
            failures.add("a cancellation inside one scope did not reach every job of the scope");
        }
        JobScheduler gated = new JobScheduler();
        gated.begin(graph);
        JobScheduler.Gate gate = gated.arm(1_000L);
        boolean beforeDeadline = JobScheduler.expired(gate, 999L);
        boolean atDeadline = JobScheduler.expired(gate, 1_000L);
        lines.add("selftest.scheduler_gate=" + (beforeDeadline ? 1 : 0) + "/" + (atDeadline ? 1 : 0)
            + " timeout_cancels=" + gated.noteTimedOut(nodeIdOf(graph, "a/0")).cancelled().size());
        if (beforeDeadline || !atDeadline) {
            failures.add("the hard timeout gate answered a reading the executor did not hand it");
        }

        JobIntake intake = new JobIntake(() -> 2);
        boolean firstAdmission = intake.submit(declarations.get(0)).accepted();
        intake.submit(declarations.get(1));
        JobIntake.Admission refusal = intake.submit(declarations.get(2));
        List<JobDeclaration> takenBatch = intake.take();
        lines.add("selftest.jobintake=" + (firstAdmission ? 1 : 0) + "/"
            + (refusal.accepted() ? 1 : 0) + "/" + refusal.code() + " depth=" + intake.depth()
            + " taken=" + takenBatch.size());
        if (!firstAdmission || refusal.accepted() || refusal.code() != RejectCode.QUEUE_CAP_EXCEEDED
            || takenBatch.size() != 2 || intake.depth() != 0) {
            failures.add("the bounded intake did not refuse past its declared capacity");
        }

        SharePlanner planner = new SharePlanner();
        ShareTable table = planner.plan(List.of(world), tick, Map.of());
        TickPlanPlanner.Input input = new TickPlanPlanner.Input(tick, 1L, 1L, List.of(world),
            List.of("entity"), TickPlanStore.Control.of(tick - 1L, 0L, 1L, 1.0, 0L, 0L, 4.0, "normal"),
            declarations, table, 64, TickPlanStore.Feedback.none());
        TickPlanPlanner.Result planned = TickPlanPlanner.plan(input);
        TickPlanPlanner.Result again = TickPlanPlanner.plan(input);
        lines.add("selftest.plan_nodes=" + (planned.ok() ? planned.plan().graph().nodeCount() : -1)
            + " steps=" + (planned.ok() ? planned.plan().commitOrder().size() : -1)
            + " modes=" + (planned.ok() ? planned.plan().domainModes().size() : -1)
            + " unknown_sites=" + (planned.ok() ? planned.plan().unknownSites() : -1));
        lines.add("selftest.plan_hash_equal=" + (planned.ok() && again.ok()
            && planned.plan().contentHash() == again.plan().contentHash() ? 1 : 0)
            + " hash=" + (planned.ok() ? Long.toHexString(planned.plan().contentHash()) : "none"));
        if (!planned.ok() || !again.ok()
            || planned.plan().contentHash() != again.plan().contentHash()) {
            failures.add("two plans of the same input did not fold to the same content hash");
        }
        if (planned.ok()) {
            TickPlan plan = planned.plan();
            boolean ascending = true;
            int previous = -1;
            for (TickPlan.CommitStep step : plan.commitOrder()) {
                if (step.position() <= previous) {
                    ascending = false;
                }
                previous = step.position();
            }
            TickPlan.DomainMode mode = plan.modeOf(world, "entity");
            lines.add("selftest.plan_commit_order=" + ascending + " first="
                + plan.commitOrder().get(0).position() + " last="
                + plan.commitOrder().get(plan.commitOrder().size() - 1).position()
                + " mode=" + mode.mode() + "/" + mode.reason());
            if (!ascending || mode.mode() != TickPlan.Mode.CONSERVATIVE
                || mode.reason() != TickPlan.Reason.UNKNOWN_SITE) {
                failures.add("the plan did not freeze a rising commit order or the conservative mode");
            }
        }
        TickPlanPlanner.Result noControl = TickPlanPlanner.plan(new TickPlanPlanner.Input(tick, 1L, 1L,
            List.of(world), List.of("entity"), TickPlanStore.Control.missing(), declarations, table,
            64, TickPlanStore.Feedback.none()));
        TickPlanPlanner.Result rollback = TickPlanPlanner.plan(new TickPlanPlanner.Input(tick, 1L, 0L,
            List.of(world), List.of("entity"),
            TickPlanStore.Control.of(tick - 1L, 0L, 1L, 1.0, 0L, 0L, 4.0, "normal"), declarations,
            table, 64, TickPlanStore.Feedback.none()));
        TickPlanPlanner.Result regression = TickPlanPlanner.plan(new TickPlanPlanner.Input(tick, 0L, 1L,
            List.of(world), List.of("entity"),
            TickPlanStore.Control.of(tick - 1L, 0L, 1L, 1.0, 0L, 0L, 4.0, "normal"), declarations,
            table, 64, TickPlanStore.Feedback.none()));
        lines.add("selftest.plan_refusals=" + codeOf(noControl) + "/" + codeOf(rollback) + "/"
            + codeOf(regression));
        if (noControl.code() != RejectCode.COUNTER_MISSING
            || rollback.code() != RejectCode.WORLD_LIFECYCLE_DENIED
            || regression.code() != RejectCode.COMMIT_ORDER_VIOLATION) {
            failures.add("the planning period built a plan from an input it must refuse");
        }

        TickPlanStore store = new TickPlanStore(2);
        store.noteControl(input.control());
        if (planned.ok()) {
            store.publish(planned.plan());
            store.noteControl(TickPlanStore.Control.of(tick, 1L, 1L, 1.0, 0L, 0L, 4.0, "normal"));
        }
        TickPlanStore.StepRef resolved = store.resolve(world, "entity", "a/2");
        TickPlanStore.StepRef missing = store.resolve(world, "entity", "nobody");
        lines.add("selftest.plan_store=" + (resolved == null ? "none"
            : resolved.planSequence() + ":" + resolved.position()) + " unresolved="
            + store.unresolvedLookups() + " control=" + store.control().planSequence()
            + " rate=" + fmt(store.failureRate()));
        if (resolved == null || missing != null || store.plansBuilt() != 1L) {
            failures.add("the plan store did not resolve a planned key or resolved an unknown one");
        }

        CommitLog log = new CommitLog(() -> 4, (lineWorld, domain, key) -> {
            TickPlanStore.StepRef step = store.resolve(lineWorld, domain, key);
            return step == null ? null
                : new CommitLog.Resolved(step.planSequence(), step.position(), step.intent());
        });
        log.beginTick(tick);
        CommitLog.Verdict accepted = log.reach(new CommitLog.Batch(world, "entity", "a/0",
            CommitLog.Batch.Kind.APPLY, 1L, 11L, 2));
        CommitLog.Verdict later = log.reach(new CommitLog.Batch(world, "entity", "a/2",
            CommitLog.Batch.Kind.APPLY, 1L, 12L, 2));
        CommitLog.Verdict misordered = log.reach(new CommitLog.Batch(world, "entity", "a/1",
            CommitLog.Batch.Kind.APPLY, 1L, 13L, 2));
        CommitLog.Verdict unplanned = log.reach(new CommitLog.Batch(world, "entity", "nobody",
            CommitLog.Batch.Kind.APPLY, 1L, 14L, 1));
        CommitLog.Verdict intent = log.reach(new CommitLog.Batch(world, "intent", world + "/intent",
            CommitLog.Batch.Kind.INTENT, 7L, 7L, 1));
        CommitLog.Replay replay = log.closeTick();
        lines.add("selftest.commit_verdicts=" + accepted.disposition() + "/"
            + later.disposition() + "/"
            + (misordered.code() == null ? "none" : misordered.code().text()) + "/"
            + (unplanned.code() == null ? "none" : unplanned.code().text()) + "/"
            + intent.disposition() + " divergence=" + log.firstDivergencePosition());
        lines.add("selftest.commit_counters=" + log.accepted() + "/" + log.intents() + "/"
            + log.dropped() + " violations=" + log.orderViolations() + " unplanned="
            + log.unplanned() + " rings=" + log.ringCount() + " steps=" + replay.loggedSteps()
            + " matches=" + (replay.orderMatches() ? 1 : 0));
        // The run carries one injected violation, so the tick must report that its order did not
        // match the plan: a certificate that stayed clean after an out-of-order commit would be
        // exactly the false green the invariant exists to prevent.
        if (!accepted.logged() || !later.logged()
            || misordered.code() != RejectCode.COMMIT_ORDER_VIOLATION
            || unplanned.code() != RejectCode.COMMIT_ORDER_VIOLATION || log.orderViolations() != 1L
            || log.unplanned() != 1L || replay.orderMatches()) {
            failures.add("the commit log did not judge an out-of-order or unplanned commit");
        }

        CommitLog bounded = new CommitLog(() -> 1, (lineWorld, domain, key) ->
            new CommitLog.Resolved(1L, 0, false));
        bounded.beginTick(tick);
        bounded.reach(new CommitLog.Batch(world, "entity", "a/0", CommitLog.Batch.Kind.APPLY, 1L, 1L, 1));
        CommitLog.Verdict full = bounded.reach(new CommitLog.Batch(world, "entity", "a/0",
            CommitLog.Batch.Kind.APPLY, 1L, 2L, 1));
        lines.add("selftest.commit_ring_full=" + full.code() + " capacity="
            + bounded.ringCapacity() + " depth=" + bounded.ringDepth());
        if (full.code() != RejectCode.QUEUE_CAP_EXCEEDED || bounded.refusedFull() != 1L) {
            failures.add("a full commit ring did not refuse instead of growing");
        }

        CommitLog replayed = new CommitLog(() -> 8, (lineWorld, domain, key) -> {
            TickPlanStore.StepRef step = store.resolve(lineWorld, domain, key);
            return step == null ? null
                : new CommitLog.Resolved(step.planSequence(), step.position(), step.intent());
        });
        replayed.beginTick(tick);
        replayed.reach(new CommitLog.Batch(world, "entity", "a/0", CommitLog.Batch.Kind.APPLY, 1L, 11L, 2));
        replayed.reach(new CommitLog.Batch(world, "entity", "a/2", CommitLog.Batch.Kind.APPLY, 1L, 12L, 2));
        CommitLog.Replay sameRun = replayed.closeTick();
        CommitLog perturbed = new CommitLog(() -> 8, (lineWorld, domain, key) -> {
            TickPlanStore.StepRef step = store.resolve(lineWorld, domain, key);
            return step == null ? null
                : new CommitLog.Resolved(step.planSequence(), step.position(), step.intent());
        });
        perturbed.beginTick(tick);
        perturbed.reach(new CommitLog.Batch(world, "entity", "a/0", CommitLog.Batch.Kind.APPLY, 1L, 11L, 2));
        perturbed.reach(new CommitLog.Batch(world, "entity", "a/2", CommitLog.Batch.Kind.APPLY, 1L, 999L, 2));
        CommitLog.Replay otherRun = perturbed.closeTick();
        lines.add("selftest.commit_replay_compare=" + CommitLog.compareRuns(sameRun, replayed.last())
            + "/" + CommitLog.compareRuns(sameRun, otherRun));
        if (CommitLog.compareRuns(sameRun, replayed.last()) != 0
            || CommitLog.compareRuns(sameRun, otherRun) == 0) {
            failures.add("the replay comparison did not tell a repeated run from a changed one");
        }

        ShareMeterPoint point = new ShareMeterPoint();
        point.note(SelfClass.ENTITY, world, "job-graph", 2_000_000L);
        long[] totals = new long[SelfClass.values().length];
        totals[SelfClass.ENTITY.ordinal()] = 2_000_000L;
        ShareMeter.TickReading reading = ShareMeterPoint.read(tick, true, Map.of(world, totals));
        lines.add("selftest.jobmeter_notes=" + point.notes() + " classes=" + point.classes()
            + " entity_ms=" + fmt(reading.row(ShareClass.ENTITY).usedMs()) + " share_class="
            + ShareMeterPoint.shareClassOf(SelfClass.ENTITY).key());
        if (point.notes() != 1L || point.classes() != 1
            || Math.abs(reading.row(ShareClass.ENTITY).usedMs() - 2.0) > 1.0e-9) {
            failures.add("the metering point of the job layer did not reach the share reading");
        }
        return lines;
    }

    private static JobDeclaration declaration(String key, String world, String domain, String region,
                                              int priority, ShareClass shareClass,
                                              JobDeclaration.SiteClass siteClass,
                                              List<String> predecessors, int bound) {
        JobDeclaration.DomainRef ref = new JobDeclaration.DomainRef(world, domain, 0);
        return new JobDeclaration(key, key.hashCode(), world, domain, 0, predecessors, priority,
            region, "region", List.of(ref), List.of(ref), shareClass, siteClass, 0, bound);
    }

    private static long nodeIdOf(JobGraph graph, String key) {
        JobGraph.Node node = graph.nodeByKey(key);
        return node == null ? -1L : node.nodeId();
    }

    private static String codeOf(JobGraphBuilder.Freeze freeze) {
        return freeze.code() == null ? "ok" : freeze.code().text();
    }

    private static String codeOf(TickPlanPlanner.Result result) {
        return result.code() == null ? "ok" : result.code().text();
    }

    private static String joinIds(List<Long> ids) {
        StringBuilder builder = new StringBuilder();
        for (Long id : ids) {
            if (builder.length() > 0) {
                builder.append(",");
            }
            builder.append(id);
        }
        return builder.toString();
    }

    private static ShareTable squeeze(ShareTable table, double budgetMs) {
        return new ShareTable(table.tickIndex(), table.rows(), table.reserve(),
            table.hostOverheadMs(), budgetMs);
    }

    private static ShareTable drawnReserve(ShareTable table) {
        double drawn = Math.max(1.0, table.reserve().reserveMs());
        return new ShareTable(table.tickIndex(), table.rows(),
            new ShareTable.ReserveRow(drawn, drawn, Map.of()), table.hostOverheadMs(),
            table.eBudgetMs());
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    /** Runs the self checks the installed domains contribute; a domain that is not installed
     * contributes none. */
    private static List<String> domainSelfChecks(List<String> failures, long tick) {
        List<String> lines = new ArrayList<>();
        for (KernelDomain domain : KernelModule.instance().domains()) {
            lines.addAll(domain.selfCheck(failures, tick));
        }
        return lines;
    }

    /** The three rungs of the wait ladder: the four items each carries, the walk that may not skip,
     * the counters a reached rung publishes while no action may run, and the return gate that needs
     * both the clean run and the recovered progress signal. The injection walkthrough is checked
     * beside it, because a class of call sites that no injection walked is a class nothing proved. */
    private static List<String> waitLadderMatrix(List<String> failures, long tick) {
        List<String> lines = new ArrayList<>();
        WaitLadder quiet = new WaitLadder(() -> false);
        int complete = 0;
        for (WaitLadder.Rung rung : quiet.rungs()) {
            if (!rung.trigger().isBlank() && !rung.action().isBlank() && !rung.signal().isBlank()
                && !rung.returnCondition().isBlank()) {
                complete++;
            }
        }
        WaitLadder.Advance walk = quiet.noteEntered(WaitLadder.Level.A1, tick);
        WaitLadder.Advance further = quiet.noteEntered(WaitLadder.Level.A2, tick);
        lines.add("selftest.wait_ladder_rungs=" + quiet.rungs().size() + "/" + complete);
        lines.add("selftest.wait_ladder_walked=" + walk.entered().size() + "+"
            + further.entered().size() + " skipped=" + (walk.skipped() ? 1 : 0)
            + (further.skipped() ? 1 : 0) + " deepest=" + further.reached());
        lines.add("selftest.wait_ladder_entered_a2=" + quiet.counters(WaitLadder.Level.A2).entered());
        lines.add("selftest.wait_ladder_effective_off="
            + (quiet.noteEffective(WaitLadder.Level.A2) ? 1 : 0));
        WaitLadder.Gate early = quiet.gate(WaitLadder.Level.A1, 3);
        for (int index = 0; index < 3; index++) {
            quiet.noteTick(false);
        }
        WaitLadder.Gate unbound = quiet.gate(WaitLadder.Level.A2, 3);
        WaitLadder acting = new WaitLadder(() -> true);
        acting.bindSecondCondition(WaitLadder.Level.A1, "selftest.progress", () -> true);
        acting.noteEntered(WaitLadder.Level.A1, tick);
        for (int index = 0; index < 3; index++) {
            acting.noteTick(false);
        }
        WaitLadder.Gate ready = acting.gate(WaitLadder.Level.A1, 3);
        boolean effective = acting.noteEffective(WaitLadder.Level.A1);
        boolean returned = acting.noteReturned(WaitLadder.Level.A1);
        lines.add("selftest.wait_ladder_gate=" + early.blockedBy() + "/" + unbound.blockedBy() + "/"
            + ready.blockedBy() + "/" + (ready.ready() ? 1 : 0));
        lines.add("selftest.wait_ladder_acting=" + (effective ? 1 : 0) + "/" + (returned ? 1 : 0)
            + " effective=" + acting.effectiveTotal() + " returned=" + acting.returnedTotal());
        WaitLadder skipping = new WaitLadder(() -> true);
        WaitLadder.Advance jumped = skipping.noteEntered(WaitLadder.Level.A3, tick);
        lines.add("selftest.wait_ladder_skip=" + (jumped.skipped() ? 1 : 0) + " walked="
            + jumped.entered().size());
        if (quiet.rungs().size() != 3 || complete != 3) {
            failures.add("a rung of the wait ladder does not carry all four items");
        }
        if (walk.entered().size() != 1 || further.entered().size() != 1 || walk.skipped()
            || further.skipped()) {
            failures.add("the wait ladder did not walk its rungs one at a time in order");
        }
        if (quiet.noteEffective(WaitLadder.Level.A2)) {
            failures.add("a wait rung reported an action while its switch was off");
        }
        if (!"clean_ticks".equals(early.blockedBy())
            || !"second_condition_unbound".equals(unbound.blockedBy()) || !ready.ready()) {
            failures.add("the wait return gate did not answer both of its conditions");
        }
        if (!effective || !returned) {
            failures.add("a wait rung did not report its action while the switch was on");
        }
        if (!jumped.skipped() || jumped.entered().size() != 3) {
            failures.add("a single call that skipped rungs was not counted as a skip");
        }
        waitWalkthroughMatrix(lines, failures);
        return lines;
    }

    /** The injection walkthrough, counted per class of call site: the two classes the inventory
     * places, the call sites that fall in neither, and the two totals an acceptance line reads. */
    private static void waitWalkthroughMatrix(List<String> lines, List<String> failures) {
        WaitPointRegistry registry = new WaitPointRegistry(() -> 50);
        int tickPathSites = 0;
        int commandSites = 0;
        int unplaced = 0;
        for (WaitSite site : registry.sites().sites()) {
            WaitClass placed = WaitClass.ofPhase(site.tickPhase());
            if (placed == WaitClass.TICK_PATH) {
                tickPathSites++;
            } else if (placed == WaitClass.COMMAND_LIFECYCLE) {
                commandSites++;
            } else {
                unplaced++;
            }
        }
        WaitClass tickPath = WaitClass.classified().get(0);
        WaitClass commandFace = WaitClass.classified().get(1);
        registry.noteInjectionWalkthrough("chunk", tickPath);
        registry.noteInjectionWalkthrough("region", commandFace);
        lines.add("selftest.wait_site_classes=" + tickPathSites + "/" + commandSites + "/" + unplaced);
        lines.add("selftest.wait_walkthrough_keys=" + tickPath.key() + commandFace.key());
        lines.add("selftest.wait_walkthrough_classes=" + registry.walkthroughOf(tickPath) + "/"
            + registry.walkthroughOf(commandFace));
        if (tickPathSites == 0 || commandSites == 0 || unplaced != 0) {
            failures.add("the call site inventory does not place every site in a wait class");
        }
        if (registry.walkthroughOf(tickPath) < 1L || registry.walkthroughOf(commandFace) < 1L) {
            failures.add("an injection walkthrough did not land in its call-site class");
        }
    }

    /** The round trip of the two reserved arena shapes. The negative controls - a dropped passthrough
     * and a write that names a stale version - run on a carrier of their own, so the counters the
     * readout publishes stay a measurement of the live round trip; the live one claims a real slot,
     * keeps a payload, publishes it against the generation it read and reads it back. */
    private static List<String> arenaMatrix(List<String> failures, long tick) {
        List<String> lines = new ArrayList<>();
        ArenaPassthrough scratch = new ArenaPassthrough();
        scratch.write("scratch", new byte[] {1, 2, 3});
        boolean scratchEqual = scratch.readBack("scratch");
        scratch.drop("scratch");
        long stale = scratch.generation("scratch") + 1L;
        boolean staleRefused = !scratch.publish("scratch", stale);
        ArenaPassthrough.Reading negative = scratch.reading();
        lines.add("selftest.arena_scratch_roundtrip=" + (scratchEqual ? 1 : 0));
        lines.add("selftest.arena_scratch_lost=" + negative.lost());
        lines.add("selftest.arena_scratch_version_refused=" + (staleRefused ? 1 : 0));
        lines.add("selftest.arena_scratch_version_mismatch=" + negative.versionMismatch());
        if (!scratchEqual || negative.lost() != 1L || !staleRefused
            || negative.versionMismatch() != 1L || negative.roundtripDiff() != 0L) {
            failures.add("the arena counters did not move for the negative controls");
        }

        KernelModule module = KernelModule.instance();
        ArenaPassthrough live = module.passthrough();
        ArenaLedger ledger = module.arena();
        String world = "selftest-world";
        long generation = live.write(world, "unmodelled-subtree".getBytes(StandardCharsets.UTF_8));
        boolean published = live.publish(world, generation);
        boolean held = live.readBack(world);
        ArenaSlot slot = ledger.claim(1L, world, "r0.0", 0, 8);
        boolean released = false;
        if (slot != null) {
            released = ledger.release(slot.lease(1L), true) == ArenaSlot.Release.RELEASED;
        }
        ArenaPassthrough.Reading reading = live.reading();
        lines.add("selftest.arena_live_published=" + (published ? 1 : 0));
        lines.add("selftest.arena_live_roundtrip=" + (held ? 1 : 0));
        lines.add("selftest.arena_live_slot=" + (slot == null ? 0 : 1));
        lines.add("selftest.arena_live_released=" + (released ? 1 : 0));
        lines.add("selftest.arena_live_passthrough_lost=" + reading.lost());
        lines.add("selftest.arena_live_roundtrip_diff=" + reading.roundtripDiff());
        lines.add("selftest.arena_live_version_mismatch=" + reading.versionMismatch());
        if (!published || !held || slot == null || !released) {
            failures.add("the live arena round trip did not complete");
        }
        if (reading.lost() != 0L || reading.roundtripDiff() != 0L
            || reading.versionMismatch() != 0L) {
            failures.add("the live arena round trip reported a lost passthrough or a difference");
        }
        lines.add("selftest.arena_tick=" + tick);
        return lines;
    }

    /** The two differential arms: the same rows hashed twice over one window of ticks, once as the
     * arm a run computed and once as the arm the host path produced, and then the same pair with one
     * row of the first arm deviated. Both controls run on a comparison of their own, so the pairs
     * the readout publishes stay the pairs a live merge compared; the deviated pair is part of the
     * same fixture, because a comparison that agreed on everything would prove nothing. */
    private static List<String> armMatrix(List<String> failures, long tick) {
        List<String> lines = new ArrayList<>();
        DiffProbe probe = new DiffProbe();
        List<StateHasher.Slice> computed = armRows(8);
        List<StateHasher.Slice> produced = armRows(8);
        for (int offset = 0; offset < 8; offset++) {
            probe.compare(arm(DOMAIN_ID, tick + offset, computed), arm(DOMAIN_ID, tick + offset,
                produced));
        }
        long pairsAfterAgreeing = probe.tickPairs();
        long equalAfterAgreeing = probe.equal();
        List<StateHasher.Slice> deviated = armRows(8);
        StateHasher.Slice row = deviated.get(5);
        deviated.set(5, new StateHasher.Slice(row.worldId(), row.regionId(), row.batchId(),
            row.entitySeq(), row.x(), row.y(), row.z(), row.yaw(), row.pitch(), row.velX(),
            row.velY(), row.velZ(), row.flags() ^ 1L, row.slotGeneration(), row.segmentRef()));
        probe.compare(arm(DOMAIN_ID, tick, deviated), arm(DOMAIN_ID, tick, produced));
        DiffProbe.DiffReport report = probe.report();
        lines.add("selftest.arm_agreeing_window=" + pairsAfterAgreeing + "/" + equalAfterAgreeing);
        lines.add("selftest.arm_deviated_pair=" + (report.tickPairs() - pairsAfterAgreeing) + "/"
            + (report.equal() - equalAfterAgreeing));
        lines.add("selftest.arm_fork_field=" + report.firstForkField() + " located="
            + report.locatedRows() + " unattributed=" + report.unattributed());
        KernelModule.PlanClockSeam scratchSeam = new KernelModule.PlanClockSeam();
        long seamBefore = scratchSeam.reads();
        scratchSeam.stampNanos();
        lines.add("selftest.plan_clock_seam=" + seamBefore + "->" + scratchSeam.reads());
        if (pairsAfterAgreeing != 8L || equalAfterAgreeing != 8L) {
            failures.add("the two arms that carry the same rows were not counted as equal pairs");
        }
        if (report.tickPairs() - pairsAfterAgreeing != 1L
            || report.equal() - equalAfterAgreeing != 0L) {
            failures.add("the deviated arm was not compared and refused as an unequal pair");
        }
        if (report.locatedRows() < 1L || report.firstForkEntityId() != 100005L) {
            failures.add("the differential did not locate the row the deviated arm changed");
        }
        if (scratchSeam.reads() != 1L) {
            failures.add("the planning period's clock counter did not move when the seam was used");
        }
        return lines;
    }

    private static List<StateHasher.Slice> armRows(int count) {
        List<StateHasher.Slice> rows = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            rows.add(new StateHasher.Slice("world", "r0.0", 1L, 100000L + index, index, 64.0, 0.0,
                0.0, 0.0, 0.1, 0.0, 0.0, 0L, 0L, 0L));
        }
        return rows;
    }

    private static DomainHash arm(String domainId, long tick, List<StateHasher.Slice> rows) {
        return StateHasher.hash(domainId, tick, rows, HashWhitelist.bitexact());
    }

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

    private static void runOnWorker(WorkerWrite write) {
        Thread thread = new Thread(write, "selftest-worker");
        thread.start();
        try {
            thread.join();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class WorkerWrite implements Runnable {

        private final WorldWriteGuard guard;
        private final Object levelRef;
        private final String worldId;
        private int verdict = -1;
        private boolean proceeded;
        private volatile boolean deferredApplied;

        private WorkerWrite(WorldWriteGuard guard, Object levelRef) {
            this(guard, levelRef, "world");
        }

        private WorkerWrite(WorldWriteGuard guard, Object levelRef, String worldId) {
            this.guard = guard;
            this.levelRef = levelRef;
            this.worldId = worldId;
        }

        @Override
        public void run() {
            verdict = guard.classifyBlockWrite(levelRef);
            proceeded = guard.admitBlockWrite(levelRef, worldId, () -> {
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
