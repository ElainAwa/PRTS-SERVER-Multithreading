/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.kernel.KernelModule;
import io.izzel.arclight.common.prts.kernel.arena.ArenaLedger;
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
import io.izzel.arclight.common.prts.kernel.diff.DiffProbe;
import io.izzel.arclight.common.prts.kernel.diff.HashWhitelist;
import io.izzel.arclight.common.prts.kernel.diff.StateHasher;
import io.izzel.arclight.common.prts.kernel.dispatch.CancelToken;
import io.izzel.arclight.common.prts.kernel.dispatch.DispatchPass;
import io.izzel.arclight.common.prts.kernel.dispatch.DispatchReadings;
import io.izzel.arclight.common.prts.kernel.dispatch.DispatchWriteBack;
import io.izzel.arclight.common.prts.kernel.dispatch.EntityCandidateView;
import io.izzel.arclight.common.prts.kernel.dispatch.EntityIntegrator;
import io.izzel.arclight.common.prts.kernel.dispatch.MergeSegment;
import io.izzel.arclight.common.prts.kernel.dispatch.TaskLedger;
import io.izzel.arclight.common.prts.kernel.dispatch.WorkPlan.WorkBatch;
import io.izzel.arclight.common.prts.kernel.dispatch.WorkPlan.WorkTask;
import io.izzel.arclight.common.prts.kernel.dispatch.WorkPlan;
import io.izzel.arclight.common.prts.kernel.dispatch.WorkerPool;
import io.izzel.arclight.common.prts.support.PrtsWorldWriteTaps;
import io.izzel.arclight.common.prts.kernel.codes.RejectTrigger;
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
import io.izzel.arclight.common.prts.kernel.shares.OverrunRecord;
import io.izzel.arclight.common.prts.kernel.shares.ShareClass;
import io.izzel.arclight.common.prts.kernel.shares.SharePlanner;
import io.izzel.arclight.common.prts.kernel.shares.ShareTable;
import io.izzel.arclight.common.prts.kernel.waitpoints.CoverageReport;
import io.izzel.arclight.common.prts.kernel.waitpoints.Dec19Elements;
import io.izzel.arclight.common.prts.kernel.waitpoints.WaitPointRegistry.RegisterResult;
import io.izzel.arclight.common.prts.kernel.waitpoints.WaitPointRegistry.WaitObservation;
import io.izzel.arclight.common.prts.kernel.waitpoints.WaitPointRegistry.WaitPointDeclaration;
import io.izzel.arclight.common.prts.kernel.waitpoints.WaitPointRegistry;
import io.izzel.arclight.common.prts.kernel.waitpoints.WaitPointRegistry.WaitSpan;
import io.izzel.arclight.common.prts.kernel.waitpoints.observe.WaitSiteObserver;
import io.izzel.arclight.common.prts.support.PrtsWaitSites;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

/** The check never touches the live counters: it builds its own registry, queue and ledger, drives
 * the paths a call site would drive and prints what came back. */
public final class KernelSelfCheck {

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
        lines.addAll(dispatchMatrix(failures, tick));

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
        return lines;
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

    /** Drives the written-down call site list: completeness, refusal and coverage. */
    private static List<String> dispatchMatrix(List<String> failures, long tick) {
        List<String> lines = new ArrayList<>();
        DispatchReadings readings = new DispatchReadings();
        ArenaLedger arena = new ArenaLedger();
        TaskLedger ledger = new TaskLedger(1L);
        EntityCandidateView view = dispatchFixture();
        WorkPlan plan = WorkPlan.freeze(tick, 1L, List.of(view), 4, 1L);
        WorkerPool pool = WorkerPool.open(new WorkerPool.Spec(1, "prts-worker-", Thread.NORM_PRIORITY,
            8, 4), 1, readings, arena);
        try {
            MergeSegment merge = new MergeSegment();
            merge.bindOwnerThread(Thread.currentThread());
            DispatchPass pass = DispatchPass.dispatch(plan, pool, EntityIntegrator.INSTANCE, arena,
                readings, ledger);
            MergeSegment.Frame frame = merge.merge(pass, System.nanoTime() + 2_000_000_000L, arena,
                readings, new DiffProbe(), HashWhitelist.bitexact(), "entity", null);
            lines.add("selftest.dispatch_tasks=" + plan.taskCount());
            lines.add("selftest.dispatch_worker_exec=" + readings.execByThread("prts-worker-0"));
            lines.add("selftest.dispatch_executed=" + readings.executed());
            lines.add("selftest.dispatch_committed=" + frame.committed());
            lines.add("selftest.dispatch_closure=" + (frame.closureOk() ? "ok" : "broken"));
            lines.add("selftest.dispatch_hash_equal=" + (frame.hashEqual() ? 1 : 0));
            lines.add("selftest.dispatch_pins=" + (arena.pinPairsHold() ? "ok" : "broken"));
            lines.add("selftest.dispatch_thread_class=" + pool.threadClasses().get(0).threadClass());
            if (frame == null || frame.committed() != plan.taskCount()) {
                failures.add("the dispatched batches were not committed exactly once");
            }
            if (!frame.closureOk()) {
                failures.add("the dispatch accounting does not close");
            }
            if (readings.execByThread("prts-worker-0") <= 0) {
                failures.add("no batch ran on a worker thread");
            }
            if (!frame.hashEqual()) {
                failures.add("the two arms of the state hash did not agree");
            }
            if (!arena.pinPairsHold()) {
                failures.add("an arena slot was not released");
            }
            long firstBatch = plan.tasks().get(0).batchId() + 1000L;
            ledger.register(firstBatch);
            boolean firstCommit = ledger.markCommitted(firstBatch);
            boolean secondCommit = ledger.markCommitted(firstBatch);
            lines.add("selftest.dispatch_duplicate_refused=" + (firstCommit && !secondCommit ? 1 : 0));
            if (!firstCommit || secondCommit) {
                failures.add("a second commit of one batch was not refused");
            }
            lines.addAll(dispatchFailureMatrix(failures, tick));
            lines.addAll(dispatchWriteBackMatrix(failures, tick));
        } finally {
            pool.shutdown(true, true, 500L);
            arena.reset();
        }
        return lines;
    }

    private static List<String> dispatchWriteBackMatrix(List<String> failures, long tick) {
        List<String> lines = new ArrayList<>();
        DispatchReadings readings = new DispatchReadings();
        ArenaLedger arena = new ArenaLedger();
        TaskLedger ledger = new TaskLedger(1L);
        IntentQueue intents = new IntentQueue(() -> 64, () -> 1);
        Map<String, PrtsWorldWriteTaps.DeferredWrite> store = new LinkedHashMap<>();
        List<WriteIntent> applied = new ArrayList<>();
        AtomicInteger handles = new AtomicInteger();
        intents.bindPayload(intent -> {
            applied.add(intent);
            return IntentPayload.Outcome.APPLIED;
        });
        DispatchWriteBack writeBack = new DispatchWriteBack(intents, (prefix, write) -> {
            String handle = prefix + ":" + handles.incrementAndGet();
            store.put(handle, write);
            return handle;
        }, store::remove, world -> 1L, readings, () -> true);
        WorkPlan plan = WorkPlan.freeze(tick, 1L, List.of(dispatchFixture()), 4, 1L);
        WorkerPool pool = WorkerPool.open(new WorkerPool.Spec(1, "prts-worker-", Thread.NORM_PRIORITY,
            8, 4), 1, readings, arena);
        try {
            MergeSegment merge = new MergeSegment();
            merge.bindOwnerThread(Thread.currentThread());
            DispatchPass pass = DispatchPass.dispatch(plan, pool, EntityIntegrator.INSTANCE, arena,
                readings, ledger);
            merge.merge(pass, System.nanoTime() + 2_000_000_000L, arena, readings, new DiffProbe(),
                HashWhitelist.bitexact(), "entity", writeBack);
            CommitSegment segment = new CommitSegment(intents, () -> true, () -> 64);
            segment.bindOwnerThread(Thread.currentThread());
            CommitSegment.Pass walk = segment.run(tick + 1);
            boolean tagged = applied.size() == plan.taskCount();
            for (int index = 0; index < applied.size() && tagged; index++) {
                tagged = applied.get(index).siteId().equals(DispatchWriteBack.SITE_PREFIX + ":"
                    + plan.tasks().get(index).batchId());
            }
            lines.add("selftest.dispatch_writeback_intents=" + intents.enqueuedCount());
            lines.add("selftest.dispatch_writeback_tagged=" + (tagged ? 1 : 0));
            lines.add("selftest.dispatch_writeback_cursor=" + segment.cursor());
            lines.add("selftest.dispatch_writeback_order_violations="
                + intents.orderViolationCount());
            if (intents.enqueuedCount() != plan.taskCount() || walk.steps() != plan.taskCount()) {
                failures.add("a committed batch did not become exactly one write");
            }
            if (!tagged) {
                failures.add("a write did not carry the identity of its batch in the frozen order");
            }
            if (segment.cursor() != plan.taskCount() || intents.orderViolationCount() != 0L) {
                failures.add("the commit did not consume the frozen order of the write-backs");
            }
        } finally {
            pool.shutdown(true, true, 500L);
            arena.reset();
        }
        DispatchReadings refused = new DispatchReadings();
        IntentQueue shallow = new IntentQueue(() -> 1, () -> 1);
        Map<String, PrtsWorldWriteTaps.DeferredWrite> shallowStore = new LinkedHashMap<>();
        AtomicInteger shallowHandles = new AtomicInteger();
        DispatchWriteBack shallowWriteBack = new DispatchWriteBack(shallow, (prefix, write) -> {
            String handle = prefix + ":" + shallowHandles.incrementAndGet();
            shallowStore.put(handle, write);
            return handle;
        }, shallowStore::remove, world -> 1L, refused, () -> true);
        List<StateHasher.Slice> rows = List.of(dispatchSlice(1.0));
        shallowWriteBack.enqueue(writeBackBatch(1L, rows.get(0).regionId(), rows), rows);
        shallowWriteBack.enqueue(writeBackBatch(2L, rows.get(0).regionId(), rows), rows);
        lines.add("selftest.dispatch_writeback_refused=" + refused.writeBackRefused());
        if (refused.writeBackRefused() != 1L || shallowStore.size() != 1) {
            failures.add("a write-back refused at the channel depth was not counted and forgotten");
        }
        // The default settlement is compute-only: the same rows are read back against the world and
        // nothing is handed to the channel, which is what keeps the domain from owning the state.
        DispatchReadings computeOnly = new DispatchReadings();
        IntentQueue idle = new IntentQueue(() -> 64, () -> 1);
        DispatchWriteBack computeLeg = new DispatchWriteBack(idle, (prefix, write) -> prefix,
            handle -> { }, world -> 1L, computeOnly, () -> false);
        computeLeg.settle(writeBackBatch(9L, rows.get(0).regionId(), rows), rows);
        lines.add("selftest.dispatch_settle_landed=" + idle.enqueuedCount());
        lines.add("selftest.dispatch_settle_readback=" + computeOnly.readBackPairs());
        if (idle.enqueuedCount() != 0L || computeOnly.readBackPairs() != 1L) {
            failures.add("the compute-only settlement landed a value or skipped its read back");
        }
        return lines;
    }

    private static WorkBatch writeBackBatch(long batchId, String regionId,
                                            List<StateHasher.Slice> rows) {
        WorkTask task = new WorkTask(batchId, "dispatch-selftest", regionId, batchId, 0, 1, 1L, 1L, 0,
            regionId);
        return new WorkBatch(batchId, task, 1L, dispatchFixture());
    }

    private static List<String> dispatchFailureMatrix(List<String> failures, long tick) {
        List<String> lines = new ArrayList<>();
        DispatchReadings readings = new DispatchReadings();
        ArenaLedger arena = new ArenaLedger();
        TaskLedger ledger = new TaskLedger(1L);
        EntityCandidateView slow = dispatchFixture("dispatch-late");
        WorkPlan plan = WorkPlan.freeze(tick, 1L, List.of(slow), 4, 1L);
        WorkerPool pool = WorkerPool.open(new WorkerPool.Spec(1, "prts-worker-", Thread.NORM_PRIORITY,
            1, 4), 1, readings, arena);
        try {
            DispatchPass first = DispatchPass.dispatch(plan, pool, (batch, target, token) -> {
                try {
                    Thread.sleep(120L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return EntityIntegrator.INSTANCE.run(batch, target, token);
            }, arena, readings, ledger);
            WorkPlan extra = WorkPlan.freeze(tick, 1L, List.of(dispatchFixture("dispatch-full")), 4,
            2L);
            DispatchPass refused = DispatchPass.dispatch(extra, pool, EntityIntegrator.INSTANCE,
                arena, readings, ledger);
            lines.add("selftest.dispatch_backpressure=" + (refused.entries().get(0).handle() == null
                ? 1 : 0));
            if (refused.entries().get(0).handle() != null) {
                failures.add("a full worker queue did not fall back");
            }
            first.awaitAll(System.nanoTime() + 1_000_000L);
            long dropsBefore = readings.lateResultDropped();
            joinQuietly(180L);
            lines.add("selftest.dispatch_late_dropped="
                + (readings.lateResultDropped() > dropsBefore ? 1 : 0));
            if (readings.lateResultDropped() <= dropsBefore) {
                failures.add("a result that arrived after the deadline was not dropped and counted");
            }
            lines.add("selftest.dispatch_timeouts=" + readings.timeouts());
            if (readings.timeouts() <= 0) {
                failures.add("the deadline did not cancel a batch that did not answer");
            }
        } finally {
            pool.shutdown(true, true, 500L);
            arena.reset();
        }
        List<StateHasher.Slice> one = List.of(dispatchSlice(1.0));
        List<StateHasher.Slice> same = List.of(dispatchSlice(1.0));
        List<StateHasher.Slice> other = List.of(dispatchSlice(1.0000000000000002));
        boolean equal = StateHasher.hash("entity", tick, one, HashWhitelist.bitexact()).value()
            == StateHasher.hash("entity", tick, same, HashWhitelist.bitexact()).value();
        boolean different = StateHasher.hash("entity", tick, one, HashWhitelist.bitexact()).value()
            != StateHasher.hash("entity", tick, other, HashWhitelist.bitexact()).value();
        boolean empty = !StateHasher.hash("entity", tick, List.of(), HashWhitelist.bitexact())
            .comparable();
        lines.add("selftest.dispatch_hash_exact=" + (equal && different ? 1 : 0));
        lines.add("selftest.dispatch_hash_empty_refused=" + (empty ? 1 : 0));
        if (!equal || !different) {
            failures.add("the bit-exact hash did not agree and disagree as required");
        }
        if (!empty) {
            failures.add("an empty range produced a hash instead of an error");
        }
        return lines;
    }

    private static StateHasher.Slice dispatchSlice(double x) {
        return new StateHasher.Slice("world", "r0.0", 1L, 1L, x, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
            0.0, 0L, 0L, 0L);
    }

    private static EntityCandidateView dispatchFixture() {
        return dispatchFixture("dispatch-selftest");
    }

    private static EntityCandidateView dispatchFixture(String worldId) {
        EntityCandidateView.Builder builder = EntityCandidateView.builder(worldId, 1L);
        for (int i = 0; i < 8; i++) {
            builder.add(i, 0, 0, i, 64.0, 0.0, 0.1, 0.0, 0.0, 0.01, 0.0, 0L);
        }
        return builder.build();
    }

    private static void joinQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
