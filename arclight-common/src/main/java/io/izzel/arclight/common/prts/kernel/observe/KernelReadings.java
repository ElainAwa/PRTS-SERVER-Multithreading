/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.kernel.KernelModule;
import io.izzel.arclight.common.prts.kernel.auth.OwnerRegistry;
import io.izzel.arclight.common.prts.kernel.auth.WriteLedger;
import io.izzel.arclight.common.prts.kernel.codes.DegradeLevel;
import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import io.izzel.arclight.common.prts.kernel.codes.RejectTrigger;
import io.izzel.arclight.common.prts.kernel.config.KernelSettings;
import io.izzel.arclight.common.prts.kernel.intent.CommitSegment;
import io.izzel.arclight.common.prts.kernel.intent.IntentQueue;
import io.izzel.arclight.common.prts.kernel.meter.MeterWindow;
import io.izzel.arclight.common.prts.kernel.meter.SelfClass;
import io.izzel.arclight.common.prts.kernel.meter.SelfRow;
import io.izzel.arclight.common.prts.kernel.meter.SelfTimers;
import io.izzel.arclight.common.prts.kernel.shares.ConservationCheck;
import io.izzel.arclight.common.prts.kernel.shares.OverrunRecord;
import io.izzel.arclight.common.prts.kernel.shares.ShareClass;
import io.izzel.arclight.common.prts.kernel.shares.SharePlanner;
import io.izzel.arclight.common.prts.kernel.shares.ShareTable;
import io.izzel.arclight.common.prts.kernel.sites.WriteDecision;
import io.izzel.arclight.common.prts.kernel.sites.WritePath;
import io.izzel.arclight.common.prts.kernel.sites.WritePathCounters;
import io.izzel.arclight.common.prts.kernel.sites.WorldWriteGuard;
import io.izzel.arclight.common.prts.kernel.auth.HolderKind;
import io.izzel.arclight.common.prts.kernel.codes.WriteDisposition;
import io.izzel.arclight.common.prts.kernel.sites.ThreadOrigin;
import io.izzel.arclight.common.prts.kernel.waitpoints.CoverageReport;
import io.izzel.arclight.common.prts.kernel.waitpoints.WaitPointRegistry;
import io.izzel.arclight.common.prts.kernel.waitpoints.WaitSite;
import io.izzel.arclight.common.prts.kernel.waitpoints.observe.WaitSiteReadings;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Renders the full readout of the four pieces.
 *
 * <p>The fields are observation requests: they are published so an operator and the tests can see
 * them, but they are not part of an approved counter table. Every field is published even when its
 * value is zero, because a missing zero is an observation failure. Nothing here changes state
 * beyond the queue slope, which is a difference between two reads.</p>
 *
 * <p>Names are stable and machine readable: {@code name=value}, one per line, with the name never
 * carrying a value that a checker would have to parse apart.</p>
 */
public final class KernelReadings {

    private KernelReadings() {
    }

    /**
     * Renders the full export.
     *
     * @param module the module to read
     * @return one {@code name=value} line per field
     */
    public static List<String> export(KernelModule module) {
        List<String> lines = new ArrayList<>();
        settings(lines, module);
        writeRights(lines, module);
        writePaths(lines, module);
        intentQueue(lines, module);
        tokens(lines, module);
        rejectCodes(lines, module);
        shareBudget(lines, module);
        selfTimers(lines, module);
        waitPoints(lines, module);
        control(lines, module);
        return lines;
    }

    private static void settings(List<String> lines, KernelModule module) {
        add(lines, "kernel.category_enabled", KernelSettings.enabled());
        add(lines, "kernel.enforce_unregistered_writes", KernelSettings.enforceUnregisteredWrites());
        add(lines, "kernel.self_timers", KernelSettings.selfTimers());
        add(lines, "kernel.share_table", KernelSettings.shareTable());
        add(lines, "kernel.wait_registry", KernelSettings.waitRegistry());
        add(lines, "kernel.write_path_guard", KernelSettings.writePathGuard());
        add(lines, "kernel.commit_intents", KernelSettings.commitIntents());
        add(lines, "kernel.route_unregistered_writes", KernelSettings.routeUnregisteredWrites());
        add(lines, "kernel.intent_commit_mode", module.commitSegment().mode());
        add(lines, "kernel.write_path_tap_installed",
            io.izzel.arclight.common.prts.support.PrtsWorldWriteTaps.installed() ? 1 : 0);
        add(lines, "kernel.observation_requests", 1);
        add(lines, "kernel.e_budget_ms", format(KernelSettings.eBudgetMs()));
        add(lines, "kernel.world_share_ms", format(KernelSettings.worldShareMs()));
        add(lines, "kernel.reserve_ms", format(KernelSettings.reserveMs()));
        add(lines, "kernel.host_overhead_ms", format(KernelSettings.hostOverheadMs()));
        add(lines, "kernel.intent_queue_cap", KernelSettings.intentQueueCap());
        add(lines, "kernel.wait_bound_ms", KernelSettings.waitBoundMs());
        add(lines, "kernel.retry_budget", KernelSettings.retryBudget());
        add(lines, "kernel.self_window_ticks", KernelSettings.selfWindowTicks());
        add(lines, "kernel.self_warmup_ticks", KernelSettings.selfWarmupTicks());
        add(lines, "kernel.tick_index", module.tickIndex());
    }

    private static void writeRights(List<String> lines, KernelModule module) {
        WriteLedger ledger = module.ledger();
        add(lines, "write.attempt_total", ledger.totalAttempts());
        add(lines, "write.granted_total", ledger.totalGranted());
        add(lines, "write.intent_total", ledger.totalIntent());
        add(lines, "write.denied_total", ledger.totalDenied());
        add(lines, "write.read_grant", ledger.readGrants());
        add(lines, "write.unregistered_attempt", ledger.unregisteredAttempts());
        add(lines, "write.unregistered_grant", ledger.unregisteredGrants());
        add(lines, "write.accounting_ok", ledger.accountingOk() ? 1 : 0);
        add(lines, "write.accounting_checked", ledger.checkedPairs());
        add(lines, "write.accounting_failures", ledger.accountingFailures());
        add(lines, "write.closure_pairs", ledger.pairCount());
        add(lines, "site.registry_entries", ledger.pairCount());
        add(lines, "site.registry_growth", 0);
        for (Map.Entry<String, WriteLedger.Pair> entry : ledger.pairs().entrySet()) {
            String key = safe(entry.getKey());
            WriteLedger.Pair pair = entry.getValue();
            add(lines, "write.by_world_site." + key + ".attempt", pair.attempts());
            add(lines, "write.by_world_site." + key + ".granted", pair.granted());
            add(lines, "write.by_world_site." + key + ".intent", pair.intent());
            add(lines, "write.by_world_site." + key + ".denied", pair.denied());
        }
        add(lines, "degrade.entered_by_unregistered", 0);
        add(lines, "degrade.would_degrade_total", module.shares().records().size());
        add(lines, "degrade.action_executed", module.shares().anyActionExecuted() ? 1 : 0);
    }

    private static void writePaths(List<String> lines, KernelModule module) {
        WorldWriteGuard guard = module.guard();
        WritePathCounters counters = guard.counters();
        add(lines, "write.path_guard_active", guard.active() ? 1 : 0);
        add(lines, "write.path_enforce", guard.enforcing() ? 1 : 0);
        add(lines, "write.path_routing", guard.routing() ? 1 : 0);
        add(lines, "write.path_attempt_total", counters.totalAttempts());
        add(lines, "write.path_granted_total", counters.total(WriteDisposition.GRANT));
        add(lines, "write.path_intent_total", counters.total(WriteDisposition.INTENT));
        add(lines, "write.path_denied_total", counters.total(WriteDisposition.DENY));
        add(lines, "write.path_unregistered_attempt", counters.unregisteredAttempts());
        add(lines, "write.path_accounting_ok", counters.closureHolds() ? 1 : 0);
        add(lines, "write.path_pairs_checked", counters.pairsChecked());
        add(lines, "write.undeclared_threads", guard.undeclaredThreads());
        add(lines, "write.declared_holders", guard.declaredHolders());
        for (WritePath path : WritePath.values()) {
            add(lines, "write.path." + path.key() + ".attempts", counters.attemptsAt(path));
            for (ThreadOrigin origin : ThreadOrigin.values()) {
                for (HolderKind holder : HolderKind.values()) {
                    String prefix = "write.path." + path.key() + "." + origin.key() + "."
                        + holder.name().toLowerCase(Locale.ROOT) + ".";
                    add(lines, prefix + "attempts", counters.attempts(path, origin, holder));
                    for (WriteDisposition disposition : WriteDisposition.values()) {
                        add(lines, prefix + disposition.name().toLowerCase(Locale.ROOT),
                            counters.count(path, origin, holder, disposition));
                    }
                }
            }
        }
        WriteDecision last = guard.lastDecision();
        add(lines, "write.last_decision.present", last == null ? 0 : 1);
        add(lines, "write.last_decision.path", last == null ? "none" : last.path().key());
        add(lines, "write.last_decision.origin", last == null ? "none" : last.origin().key());
        add(lines, "write.last_decision.holder", last == null ? "none"
            : last.holder().name().toLowerCase(Locale.ROOT));
        add(lines, "write.last_decision.disposition", last == null ? "none"
            : last.disposition().name().toLowerCase(Locale.ROOT));
        add(lines, "write.last_decision.code", last == null || last.code() == null ? "none"
            : last.code().text());
        add(lines, "write.last_decision.site", last == null ? "none" : last.siteId());
        add(lines, "write.last_decision.thread", last == null ? "none" : last.threadRef());
        add(lines, "write.last_decision.world", last == null ? "none" : last.worldId());
        add(lines, "write.last_decision.tick", last == null ? 0L : last.tickIndex());
        add(lines, "write.payload_applied", guard.payloads().appliedCount());
        add(lines, "write.payload_failed", guard.payloads().failedCount());
        add(lines, "write.payload_threw", guard.payloads().threwCount());
        add(lines, "write.payload_unbound", guard.payloads().unboundCount());
        add(lines, "write.payload_dropped", guard.payloads().droppedCount());
        add(lines, "write.payload_pending", guard.payloads().pendingCount());
    }

    private static void intentQueue(List<String> lines, KernelModule module) {
        IntentQueue intents = module.intents();
        CommitSegment segment = module.commitSegment();
        add(lines, "intent.queue_depth", intents.depth());
        add(lines, "intent.queue_cap", intents.capacity());
        add(lines, "intent.queue_slope", intents.depthSlope());
        add(lines, "intent.enqueued", intents.enqueuedCount());
        add(lines, "intent.committed", intents.committedCount());
        add(lines, "intent.rejected_full", intents.rejectedFullCount());
        add(lines, "intent.order_violations", intents.orderViolationCount());
        add(lines, "intent.executed", intents.executedCount());
        add(lines, "intent.payload_refusals", intents.payloadRefusalCount());
        add(lines, "intent.last_exec_tick", intents.lastExecTick());
        add(lines, "intent.commit_mode", segment.mode());
        add(lines, "intent.commit_passes", segment.passes());
        add(lines, "intent.commit_steps", segment.steps());
        add(lines, "intent.commit_cursor", segment.cursor());
        add(lines, "intent.commit_refusals", segment.refusals());
    }

    private static void tokens(List<String> lines, KernelModule module) {
        OwnerRegistry owners = module.owners();
        add(lines, "token.active", owners.activeTokens());
        add(lines, "token.acquired", owners.acquiredCount());
        add(lines, "token.released", owners.releasedCount());
        add(lines, "token.expired_reclaimed", owners.expiredReclaimedCount());
        add(lines, "token.double_holder", owners.doubleHolderCount());
    }

    private static void rejectCodes(List<String> lines, KernelModule module) {
        for (RejectCode code : RejectCode.values()) {
            add(lines, "reject." + code.text(), module.ledger().codeCount(code));
        }
        add(lines, "reject.trigger_rows", RejectTrigger.values().length);
        add(lines, "reject.new_codes", 0);
    }

    private static void shareBudget(List<String> lines, KernelModule module) {
        SharePlanner shares = module.shares();
        ShareTable table = shares.lastTable();
        ConservationCheck conservation = module.conservation();
        add(lines, "budget.share_rows", table == null ? 0 : table.rows().size());
        add(lines, "budget.dim_rows", 2);
        add(lines, "budget.overrun_dims", 2);
        add(lines, "budget.conservation_ok", conservation.ok() ? 1 : 0);
        add(lines, "budget.conservation_over_ms", format(conservation.overByMs()));
        add(lines, "budget.e_budget_ms", format(KernelSettings.eBudgetMs()));
        add(lines, "budget.host_overhead_ms", format(KernelSettings.hostOverheadMs()));
        add(lines, "budget.sum_shares_ms", table == null ? "0.000" : format(table.sumSharesMs()));
        add(lines, "budget.sum_used_ms", table == null ? "0.000" : format(table.sumUsedMs()));
        if (table != null) {
            for (ShareClass shareClass : ShareClass.values()) {
                double share = 0.0;
                double used = 0.0;
                for (ShareTable.ShareRow row : table.rows()) {
                    if (row.shareClass() == shareClass) {
                        share += row.shareMs();
                        used += row.usedMs();
                    }
                }
                add(lines, "budget." + shareClass.key() + "_share_ms", format(share));
                add(lines, "budget." + shareClass.key() + "_used_ms", format(used));
                add(lines, "budget." + shareClass.key() + "_margin_ms", format(share - used));
            }
            for (String world : table.worlds()) {
                for (ShareTable.ShareRow row : table.rowsOf(world)) {
                    String prefix = "budget.by_world." + safe(world) + "." + row.shareClass().key();
                    add(lines, prefix + ".share_ms", format(row.shareMs()));
                    add(lines, prefix + ".used_ms", format(row.usedMs()));
                    add(lines, prefix + ".margin_ms", format(row.marginMs()));
                }
            }
        } else {
            for (ShareClass shareClass : ShareClass.values()) {
                add(lines, "budget." + shareClass.key() + "_share_ms", "0.000");
                add(lines, "budget." + shareClass.key() + "_used_ms", "0.000");
                add(lines, "budget." + shareClass.key() + "_margin_ms", "0.000");
            }
        }
        add(lines, "budget.reserve_row", table == null ? 0 : 1);
        add(lines, "budget.reserve_ms", table == null ? format(KernelSettings.reserveMs())
            : format(table.reserve().reserveMs()));
        add(lines, "budget.reserve_used_ms", table == null ? "0.000"
            : format(table.reserve().usedMs()));
        add(lines, "budget.reserve_remaining_ms", table == null ? format(KernelSettings.reserveMs())
            : format(table.reserve().remainingMs()));
        add(lines, "budget.reserve_borrowed", shares.reserveBorrowedCount());
        add(lines, "budget.hunger_events", shares.hungerEventCount());
        add(lines, "budget.would_degrade_level", degradeLevel(shares));
        add(lines, "budget.action_executed", shares.anyActionExecuted() ? 1 : 0);
        add(lines, "budget.over_class_total", shares.classOverrunTotal());
        add(lines, "budget.over_world_total", shares.worldOverrunTotal());
        for (ShareClass shareClass : ShareClass.values()) {
            add(lines, "budget.over_class_" + shareClass.key(), shares.classOverrunCount(shareClass));
        }
        for (String world : shares.overrunWorlds()) {
            add(lines, "budget.over_world_" + safe(world), shares.worldOverrunCount(world));
        }
    }

    private static String degradeLevel(SharePlanner shares) {
        DegradeLevel highest = DegradeLevel.NONE;
        for (OverrunRecord record : shares.records()) {
            if (record.wouldDegradeLevel() != null
                && record.wouldDegradeLevel().ordinal() > highest.ordinal()) {
                highest = record.wouldDegradeLevel();
            }
        }
        return highest.name();
    }

    private static void selfTimers(List<String> lines, KernelModule module) {
        MeterWindow window = module.window();
        add(lines, "self.timer_rows", SelfTimers.timerRows());
        add(lines, "self.window_ticks", window.windowTicks());
        add(lines, "self.warmup", window.warmup() ? 1 : 0);
        add(lines, "self.missing_classes", window.missingClasses());
        add(lines, "self.sample_rate", format(window.sampleRate()));
        add(lines, "self.lost_samples", window.lostSamples());
        add(lines, "self.observe_ms", format(window.observeMs()));
        add(lines, "self.unclassified_ms", format(window.unclassifiedMs()));
        add(lines, "self.total_ms", format(window.totalMs()));
        add(lines, "self.wait_total_ms", format(window.waitTotalMs()));
        add(lines, "self.wait_max_ms", format(window.waitMaxMs()));
        add(lines, "self.wait_observations", window.waitObservations());
        for (SelfRow row : window.rows()) {
            String key = row.selfClass().key();
            add(lines, "self." + key + "_ms", format(row.totalMs()));
            add(lines, "self." + key + "_share_pct", format(row.sharePct()));
            add(lines, "self." + key + "_p50_ms", format(row.p50Ms()));
            add(lines, "self." + key + "_p99_ms", format(row.p99Ms()));
        }
        add(lines, "self.asserted_classes", SelfClass.assertedCount());
    }

    private static void waitPoints(List<String> lines, KernelModule module) {
        WaitPointRegistry registry = module.waitPoints();
        CoverageReport coverage = registry.reportCoverage();
        add(lines, "wp.registered_total", coverage.registeredTotal());
        add(lines, "wp.unregistered", coverage.unregistered());
        add(lines, "wp.dec19_coverage_pct", format(coverage.coveragePct()));
        add(lines, "wp.site_inventory_total", coverage.siteInventoryTotal());
        add(lines, "wp.forced_convergence", coverage.forcedConvergence());
        for (Map.Entry<String, Integer> entry : coverage.injectionWalkthrough().entrySet()) {
            add(lines, "wp.injection_walkthrough_" + entry.getKey(), entry.getValue());
        }
        for (Map.Entry<String, Long> entry : registry.progressReadings().entrySet()) {
            add(lines, "wp.progress." + entry.getKey(), entry.getValue());
        }
        add(lines, "wp.unregistered_todo", registry.unregisteredTodo().size());
        add(lines, "wp.site_inventory_total", coverage.siteInventoryTotal());
        add(lines, "wp.site_registered", coverage.siteRegistered());
        add(lines, "wp.site_unregistered", coverage.siteUnregistered());
        add(lines, "wp.site_uncovered", coverage.siteUncoveredIds().size());
        add(lines, "wp.site_coverage_pct", format(coverage.siteCoveragePct()));
        add(lines, "wp.site_call_sites", registry.sites().callSites());
        add(lines, "wp.site_pending_list", join(coverage.sitePendingElements()));
        add(lines, "wp.site_unregistered_list", join(registry.sites().observedWithoutRow()));
        add(lines, "wp.site_uncovered_list", join(coverage.siteUncoveredIds()));
        for (WaitSite site : registry.sites().sites()) {
            String prefix = "wp.site." + safe(site.siteId()) + ".";
            add(lines, prefix + "wp", site.wpId());
            add(lines, prefix + "phase", site.tickPhase());
            add(lines, prefix + "share_class", site.shareClass());
            add(lines, prefix + "calls", site.callSites());
        }
        add(lines, "wait.cap_defined", KernelSettings.waitBoundMs() > 0 ? 1 : 0);
        add(lines, "wait.bound_ms", KernelSettings.waitBoundMs());
        add(lines, "wait.max_ms", registry.maxWaitMs());
        add(lines, "wait.overrun", registry.waitOverrunCount());
        add(lines, "wait.observed", registry.observationCount());
        waitSiteReadings(lines, module);
    }

    /**
     * Publishes what the waits of the real call sites added up to, site by site.
     *
     * <p>Every site of the written-down list gets its row, zero included: a site that never waited
     * states that as a number rather than by being absent. The two verdicts are published next to the
     * duration they belong to, and neither of them is acted on.</p>
     */
    private static void waitSiteReadings(List<String> lines, KernelModule module) {
        WaitSiteReadings readings = module.waitSites().readings();
        add(lines, "wait.over_one_tick", readings.overOneTickTotal());
        add(lines, "wait.forced_convergence_candidates", readings.convergenceCandidateTotal());
        for (int index = 0; index < readings.siteCount(); index++) {
            String siteId = safe(readings.siteIds()[index]);
            String prefix = "wait.site." + siteId + ".";
            add(lines, "wait.observed_by_site." + siteId, readings.observed(index));
            add(lines, prefix + "wait_point",
                module.waitSites().waitPointId(index) == null ? "UNREGISTERED"
                    : module.waitSites().waitPointId(index));
            add(lines, prefix + "call_site",
                module.waitSites().callSiteRef(index) == null ? "unknown"
                    : module.waitSites().callSiteRef(index));
            add(lines, prefix + "max_ms", readings.maxMs(index));
            add(lines, prefix + "over_one_tick", readings.overOneTick(index));
            add(lines, prefix + "forced_convergence_candidates",
                readings.convergenceCandidates(index));
        }
    }

    private static void control(List<String> lines, KernelModule module) {
        KernelModule.ControlFrame frame = module.control();
        add(lines, "control.tick", frame.tickIndex());
        add(lines, "control.min_margin_ms", format(frame.minMarginMs()));
        add(lines, "control.overrun_hits", frame.overrunHits());
        add(lines, "control.wait_bound_hits", frame.waitBoundHits());
        add(lines, "control.reserve_used_ms", format(frame.reserveUsedMs()));
        add(lines, "control.reserve_remaining_ms", format(frame.reserveRemainingMs()));
        add(lines, "control.degrade_state", frame.degradeState());
    }

    static void add(List<String> lines, String name, Object value) {
        lines.add(name + "=" + value);
    }

    static String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    static String join(List<String> values) {
        return values.isEmpty() ? "-" : String.join(",", values);
    }

    static String safe(String value) {
        StringBuilder builder = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isLetterOrDigit(character) || character == '.' || character == '_'
                || character == '-') {
                builder.append(character);
            } else {
                builder.append('_');
            }
        }
        return builder.toString();
    }
}
