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
import io.izzel.arclight.common.prts.kernel.intent.IntentQueue;
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
        CommitOrder outOfOrder = intents.commit(5L);
        CommitOrder first = intents.commit(0L);
        CommitOrder second = intents.commit(1L);
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

        lines.add("selftest.failures=" + failures.size());
        for (String failure : failures) {
            lines.add("selftest.failure=" + failure);
        }
        lines.add("selftest.result=" + (failures.isEmpty() ? "ok" : "failed"));
        return lines;
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
