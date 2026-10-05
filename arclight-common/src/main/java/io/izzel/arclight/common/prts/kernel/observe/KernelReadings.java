/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.kernel.KernelModule;
import io.izzel.arclight.common.prts.kernel.auth.OwnerRegistry;
import io.izzel.arclight.common.prts.kernel.auth.WriteLedger;
import io.izzel.arclight.common.prts.kernel.codes.DegradeLevel;
import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import io.izzel.arclight.common.prts.kernel.codes.RejectTrigger;
import io.izzel.arclight.common.prts.kernel.commit.CommitLog;
import io.izzel.arclight.common.prts.kernel.commit.CommitRing;
import io.izzel.arclight.common.prts.kernel.config.KernelSettings;
import io.izzel.arclight.common.prts.kernel.jobs.JobScheduler;
import io.izzel.arclight.common.prts.kernel.jobs.ShareMeterPoint;
import io.izzel.arclight.common.prts.kernel.plan.TickPlan;
import io.izzel.arclight.common.prts.kernel.plan.TickPlanStore;
import io.izzel.arclight.common.prts.kernel.degrade.DegradeLadder;
import io.izzel.arclight.common.prts.kernel.exits.DualExits;
import io.izzel.arclight.common.prts.kernel.safety.SafetyNet;
import io.izzel.arclight.common.prts.kernel.safety.ZeroEffectDetector;
import io.izzel.arclight.common.prts.kernel.DomainReadings;
import io.izzel.arclight.common.prts.kernel.KernelDomain;
import io.izzel.arclight.common.prts.kernel.intent.CommitSegment;
import io.izzel.arclight.common.prts.kernel.intent.IntentQueue;
import io.izzel.arclight.common.prts.kernel.meter.SelfTimers.MeterWindow;
import io.izzel.arclight.common.prts.kernel.meter.SelfClass;
import io.izzel.arclight.common.prts.kernel.meter.SelfTimers.SelfRow;
import io.izzel.arclight.common.prts.kernel.meter.SelfTimers;
import io.izzel.arclight.common.prts.kernel.shares.BudgetStateMachine;
import io.izzel.arclight.common.prts.kernel.shares.SharePlanner.ConservationCheck;
import io.izzel.arclight.common.prts.kernel.shares.OverrunRecord;
import io.izzel.arclight.common.prts.kernel.shares.ShareClass;
import io.izzel.arclight.common.prts.kernel.shares.ShareMeter;
import io.izzel.arclight.common.prts.kernel.shares.SharePlanner;
import io.izzel.arclight.common.prts.kernel.shares.ShareTable;
import io.izzel.arclight.common.prts.kernel.sites.WorldWriteGuard.WriteDecision;
import io.izzel.arclight.common.prts.kernel.sites.WritePath;
import io.izzel.arclight.common.prts.kernel.sites.WritePathCounters;
import io.izzel.arclight.common.prts.kernel.sites.WorldWriteGuard;
import io.izzel.arclight.common.prts.kernel.auth.HolderKind;
import io.izzel.arclight.common.prts.kernel.codes.WriteDisposition;
import io.izzel.arclight.common.prts.kernel.sites.ThreadOrigin;
import io.izzel.arclight.common.prts.kernel.waitpoints.CoverageReport;
import io.izzel.arclight.common.prts.kernel.waitpoints.ForcedConvergence;
import io.izzel.arclight.common.prts.kernel.waitpoints.WaitPointRegistry;
import io.izzel.arclight.common.prts.kernel.waitpoints.WaitProgress;
import io.izzel.arclight.common.prts.kernel.waitpoints.WaitSite;
import io.izzel.arclight.common.prts.kernel.waitpoints.observe.WaitSiteReadings;
import io.izzel.arclight.common.prts.support.PrtsSeams;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Renders the full readout of the four pieces. The fields are observation requests: they are
 * published so an operator and the tests can see them, but they are not part of an approved
 * counter table. */
public final class KernelReadings {

    private KernelReadings() {
    }

    /** Renders the full export. */
    public static List<String> export(KernelModule module) {
        List<String> lines = new ArrayList<>();
        settings(lines, module);
        writeRights(lines, module);
        writePaths(lines, module);
        intentQueue(lines, module);
        tokens(lines, module);
        rejectCodes(lines, module);
        shareBudget(lines, module);
        budgetStates(lines, module);
        degradation(lines, module);
        selfTimers(lines, module);
        domainReadings(lines, module);
        waitPoints(lines, module);
        contractLayers(lines, module);
        safety(lines, module);
        exits(lines, module);
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
        add(lines, "kernel.degrade_actions", KernelSettings.degradeActions());
        add(lines, "kernel.degrade_rollback_ticks", KernelSettings.degradeRollbackTicks());
        add(lines, "kernel.intent_commit_mode", module.commitSegment().mode());
        add(lines, "kernel.write_path_tap_installed",
            io.izzel.arclight.common.prts.support.PrtsWorldWriteTaps.installed() ? 1 : 0);
        add(lines, "kernel.observation_requests", 1);
        add(lines, "kernel.e_budget_ms", format(KernelSettings.eBudgetMs()));
        add(lines, "kernel.world_share_ms", format(KernelSettings.worldShareMs()));
        add(lines, "kernel.reserve_ms", format(KernelSettings.reserveMs()));
        add(lines, "kernel.host_overhead_ms", format(KernelSettings.hostOverheadMs()));
        add(lines, "kernel.intent_queue_cap", KernelSettings.intentQueueCap());
        add(lines, "kernel.commit_budget", KernelSettings.commitBudget());
        add(lines, "kernel.commit_budget_max", KernelSettings.COMMIT_BUDGET_MAX);
        add(lines, "kernel.wait_bound_ms", KernelSettings.waitBoundMs());
        add(lines, "kernel.retry_budget", KernelSettings.retryBudget());
        add(lines, "kernel.self_window_ticks", KernelSettings.selfWindowTicks());
        add(lines, "kernel.self_warmup_ticks", KernelSettings.selfWarmupTicks());
        add(lines, "kernel.tick_index", module.tickIndex());
        seams(lines);
    }

    private static void seams(List<String> lines) {
        List<PrtsSeams.SeamState> states = PrtsSeams.states(KernelSettings::categoryEnabled);
        int reachable = 0;
        int applied = 0;
        List<String> gaps = new ArrayList<>();
        List<String> gapCategories = new ArrayList<>();
        for (PrtsSeams.SeamState state : states) {
            String prefix = "kernel.seam." + state.seam().seamId() + ".";
            add(lines, prefix + "category", state.seam().category());
            add(lines, prefix + "category_enabled", state.categoryEnabled() ? 1 : 0);
            add(lines, prefix + "decided", state.decisionKnown() ? (state.decidedToApply() ? 1 : 0) : -1);
            add(lines, prefix + "applied", state.applied() ? 1 : 0);
            add(lines, prefix + "reachable", state.reachable() ? 1 : 0);
            if (state.reachable()) {
                reachable++;
            } else {
                gaps.add(state.seam().seamId());
                if (!gapCategories.contains(state.seam().category())) {
                    gapCategories.add(state.seam().category());
                }
            }
            if (state.applied()) {
                applied++;
            }
        }
        add(lines, "kernel.seam_declared", states.size());
        add(lines, "kernel.seam_reachable", reachable);
        add(lines, "kernel.seam_applied", applied);
        add(lines, "kernel.seam_gap", gaps.size());
        add(lines, "kernel.seam_gap_list", join(gaps));
        add(lines, "kernel.seam_gap_categories", join(gapCategories));
        add(lines, "kernel.judgeable", KernelSettings.enabled() && gaps.isEmpty() ? 1 : 0);
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
        add(lines, "write.accounting_in_flight", ledger.inFlightAttempts());
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
        add(lines, "write.payload_abandoned", guard.payloads().abandonedCount());
        add(lines, "write.payload_pending", guard.payloads().pendingCount());
        add(lines, "write.payload_world_stale", guard.staleWorldRefusals());
        add(lines, "write.commit_foreign_thread", guard.foreignCommits());
        add(lines, "write.world_epochs_tracked", guard.worldEpochs().tracking() ? 1 : 0);
        add(lines, "write.world_epochs_live", guard.worldEpochs().liveWorlds());
        add(lines, "write.world_epochs_changes", guard.worldEpochs().epochChanges());
        add(lines, "write.path_accounting_in_flight", counters.inFlightAttempts());
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
        add(lines, "intent.commit_ms", format(segment.walkNanos() / 1_000_000.0));
        add(lines, "intent.commit_cursor", segment.cursor());
        add(lines, "intent.commit_refusals", segment.refusals());
        add(lines, "intent.released", intents.releasedCount());
        add(lines, "intent.retry_exhausted", intents.retryExhaustedCount());
        add(lines, "intent.retry_budget", intents.retryBudget());
        add(lines, "intent.shards", intents.shardCount());
        add(lines, "intent.commit_released", segment.released());
        add(lines, "intent.commit_foreign_thread", segment.foreignRuns());
        add(lines, "intent.commit_owner_bound", segment.ownerBound() ? 1 : 0);
        add(lines, "intent.commit_owner_conflicts", segment.ownerConflicts());
        add(lines, "intent.commit_budget", segment.lastBudget());
        add(lines, "intent.commit_budget_stops", segment.budgetStops());
        add(lines, "intent.commit_last_steps", segment.lastSteps());
        add(lines, "intent.commit_last_pending", segment.lastPending());
        add(lines, "intent.commit_last_truncated", segment.lastTruncated() ? 1 : 0);
        for (String world : intents.worlds()) {
            String prefix = "intent.world." + safe(world) + ".";
            add(lines, prefix + "depth", intents.depth(world));
            add(lines, prefix + "cursor", segment.cursor(world));
        }
    }

    private static void tokens(List<String> lines, KernelModule module) {
        OwnerRegistry owners = module.owners();
        add(lines, "token.active", owners.activeTokens());
        add(lines, "token.acquired", owners.acquiredCount());
        add(lines, "token.released", owners.releasedCount());
        add(lines, "token.expired_reclaimed", owners.expiredReclaimedCount());
        add(lines, "token.double_holder", owners.doubleHolderCount());
        add(lines, "token.reclaim_passes", owners.reclaimPasses());
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
        add(lines, "budget.conservation_planned", conservation.planned() ? 1 : 0);
        add(lines, "budget.conservation_state", conservation.verdict().name().toLowerCase(Locale.ROOT));
        add(lines, "budget.conservation_item",
            conservation.item().isEmpty() ? "none" : conservation.item());
        add(lines, "budget.conservation_term_shares_ms", format(conservation.sumSharesMs()));
        add(lines, "budget.conservation_term_reserve_ms", format(conservation.reserveMs()));
        add(lines, "budget.conservation_term_host_ms", format(conservation.hostOverheadMs()));
        add(lines, "budget.conservation_planned_ms", format(conservation.plannedMs()));
        add(lines, "budget.conservation_slack_ms", format(conservation.slackMs()));
        add(lines, "budget.conservation_critical_slack_ms", format(conservation.eBudgetMs()
            * ConservationCheck.CRITICAL_SLACK_FRACTION));
        add(lines, "budget.conservation_reserve_borrowed", shares.reserveBorrowedCount());
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
        metering(lines, module);
    }

    /** The per-class metering of one tick: one row per share class, zero values included, the timer
     * rows that fed it and the timer rows that feed no class. */
    private static void metering(List<String> lines, KernelModule module) {
        ShareMeter.TickReading metering = module.metering();
        add(lines, "budget.meter.enabled", metering != null && metering.timerEnabled() ? 1 : 0);
        add(lines, "budget.meter.classes", metering == null ? 0 : metering.rowCount());
        add(lines, "budget.meter.expected_classes", ShareClass.rowCount());
        add(lines, "budget.meter.complete", metering != null && metering.complete() ? 1 : 0);
        add(lines, "budget.meter.unmapped", metering == null ? "-" : selfKeys(metering.unmapped()));
        add(lines, "budget.meter.missing", metering == null ? "-" : classKeys(metering.missing()));
        for (ShareClass shareClass : ShareClass.values()) {
            ShareMeter.ClassReading row = metering == null ? null : metering.row(shareClass);
            add(lines, "budget.meter." + shareClass.key() + ".used_ms",
                row == null ? "0.000" : format(row.usedMs()));
            add(lines, "budget.meter." + shareClass.key() + ".sample_nanos",
                row == null ? 0L : row.sampleNanos());
            add(lines, "budget.meter." + shareClass.key() + ".sources",
                row == null ? "-" : selfKeys(row.sources()));
        }
    }

    /** The three contract layers of one tick: the frozen plan and its orders, the job layer that
     * follows them and the commit log that judges what was reached. Every field here is an
     * observation request; no gate, verdict or release reads one of them. */
    private static void contractLayers(List<String> lines, KernelModule module) {
        add(lines, "plan.observation_only", 1);
        add(lines, "plan.enabled", KernelSettings.tickPlan() ? 1 : 0);
        add(lines, "plan.job_graph_enabled", KernelSettings.jobGraph() ? 1 : 0);
        add(lines, "plan.commit_log_enabled", KernelSettings.commitLog() ? 1 : 0);
        TickPlanStore store = module.plans();
        TickPlan plan = store.latest();
        TickPlanStore.Control control = store.control();
        add(lines, "plan.plans_built", store.plansBuilt());
        add(lines, "plan.failures", store.failures());
        add(lines, "plan.failure_rate", format(store.failureRate()));
        add(lines, "plan.last_failure", store.lastFailure() == null ? "none"
            : store.lastFailure().text());
        add(lines, "plan.rebuilds", store.rebuilds());
        add(lines, "plan.bootstrap_skips", store.bootstrapSkips());
        add(lines, "plan.unknown_sites", store.unknownSites());
        add(lines, "plan.history", store.historySize() + "/" + store.historyCap());
        add(lines, "plan.unresolved_lookups", store.unresolvedLookups());
        add(lines, "plan.control_observed", control.observed() ? 1 : 0);
        add(lines, "plan.control_tick", control.tickIndex());
        add(lines, "plan.control_sequence", control.planSequence());
        add(lines, "plan.control_world_generation", control.worldSetGeneration());
        add(lines, "plan.control_min_margin_ms", format(control.minMarginMs()));
        add(lines, "plan.control_overrun_hits", control.overrunHits());
        add(lines, "plan.control_wait_bound_hits", control.waitBoundHits());
        add(lines, "plan.control_reserve_remaining_ms", format(control.reserveRemainingMs()));
        add(lines, "plan.control_degrade_state", control.degradeState());
        TickPlanStore.Feedback feedback = store.feedback();
        add(lines, "plan.feedback_enabled", KernelSettings.planFeedback() ? 1 : 0);
        add(lines, "plan.feedback_taken", feedback.observed() ? 1 : 0);
        add(lines, "plan.feedback_scope", feedback.scope());
        add(lines, "plan.feedback_taken_tick", feedback.tickIndex());
        add(lines, "plan.feedback_taken_sequence", feedback.planSequence());
        add(lines, "plan.feedback_tick_failures", feedback.tickFailures());
        add(lines, "plan.feedback_tick_unknown_sites", feedback.tickUnknownSites());
        add(lines, "plan.feedback_total_failures", feedback.totalFailures());
        add(lines, "plan.feedback_total_unknown_sites", feedback.totalUnknownSites());
        add(lines, "plan.feedback_taken_failure_rate", format(feedback.failureRate()));
        TickPlanStore.Feedback consumed = plan == null ? TickPlanStore.Feedback.none()
            : plan.feedback();
        add(lines, "plan.consumed", consumed.observed() ? 1 : 0);
        add(lines, "plan.consumed_scope", consumed.scope());
        add(lines, "plan.consumed_tick", consumed.tickIndex());
        add(lines, "plan.consumed_sequence", consumed.planSequence());
        add(lines, "plan.consumed_failure_rate", format(consumed.failureRate()));
        add(lines, "plan.consumed_unknown_sites", consumed.totalUnknownSites());
        add(lines, "plan.consumed_tick_failures", consumed.tickFailures());
        add(lines, "plan.consumed_tick_unknown_sites", consumed.tickUnknownSites());
        long carried = plan == null || !consumed.observed() ? -1L
            : plan.tickIndex() - consumed.tickIndex();
        add(lines, "plan.feedback_carried_ticks", carried);
        add(lines, "plan.feedback_stale", carried > 1L ? 1 : 0);
        if (plan == null) {
            add(lines, "plan.tick", 0L);
            add(lines, "plan.sequence", 0L);
            add(lines, "plan.world_generation", 0L);
            add(lines, "plan.worlds", 0);
            add(lines, "plan.nodes", 0);
            add(lines, "plan.edges", 0);
            add(lines, "plan.affinity_groups", 0);
            add(lines, "plan.split_intents", 0);
            add(lines, "plan.unknown_sites_in_plan", 0);
            add(lines, "plan.topological_order", "-");
            add(lines, "plan.commit_order", "-");
            add(lines, "plan.commit_steps", 0);
            add(lines, "plan.modes", "-");
            add(lines, "plan.content_hash", 0L);
            add(lines, "plan.share_rows", 0);
        } else {
            add(lines, "plan.tick", plan.tickIndex());
            add(lines, "plan.sequence", plan.planSequence());
            add(lines, "plan.world_generation", plan.worldSetGeneration());
            add(lines, "plan.worlds", plan.worlds().size());
            add(lines, "plan.nodes", plan.graph().nodeCount());
            add(lines, "plan.edges", plan.graph().edgeCount());
            add(lines, "plan.affinity_groups", plan.graph().affinity().size());
            add(lines, "plan.split_intents", plan.splitIntents());
            add(lines, "plan.unknown_sites_in_plan", plan.unknownSites());
            add(lines, "plan.topological_order", joinIds(plan.topologicalOrder()));
            add(lines, "plan.commit_order", commitOrderText(plan));
            add(lines, "plan.commit_steps", plan.commitOrder().size());
            add(lines, "plan.modes", modesText(plan));
            add(lines, "plan.content_hash", Long.toHexString(plan.contentHash()));
            add(lines, "plan.share_rows", plan.shareTable() == null ? 0
                : plan.shareTable().rows().size());
        }
        JobScheduler scheduler = module.scheduler();
        add(lines, "jobs.observation_only", 1);
        add(lines, "jobs.intake_depth", module.jobIntake().depth());
        add(lines, "jobs.intake_cap", module.jobIntake().capacity());
        add(lines, "jobs.intake_submitted", module.jobIntake().submitted());
        add(lines, "jobs.intake_refused", module.jobIntake().refused());
        add(lines, "jobs.intake_taken", module.jobIntake().taken());
        add(lines, "jobs.intake_batches", module.jobIntake().takenBatches());
        add(lines, "jobs.dispatched", scheduler.dispatched());
        add(lines, "jobs.settled", scheduler.settledTotal());
        add(lines, "jobs.ready_depth", scheduler.depth());
        add(lines, "jobs.queued_peak", scheduler.queuedPeak());
        add(lines, "jobs.lanes", scheduler.lanes());
        add(lines, "jobs.backpressure_hits", scheduler.backpressureHits());
        add(lines, "jobs.cancelled", scheduler.cancelledTotal());
        add(lines, "jobs.timed_out", scheduler.timedOutTotal());
        ShareMeterPoint meter = module.jobMeter();
        add(lines, "jobs.meter_notes", meter.notes());
        add(lines, "jobs.meter_nanos", meter.nanos());
        add(lines, "jobs.meter_classes", meter.classes());
        add(lines, "jobs.meter_refused", meter.refusedNotes());
        CommitLog commits = module.commits();
        CommitLog.Replay replay = module.commitReplay();
        add(lines, "commit.observation_only", 1);
        add(lines, "commit.planes", commits.ringCount());
        add(lines, "commit.ring_capacity", commits.ringCapacity());
        add(lines, "commit.ring_depth", commits.ringDepth());
        add(lines, "commit.watermarks", commits.watermarks());
        add(lines, "commit.entries", commits.sequence());
        add(lines, "commit.accepted", commits.accepted());
        add(lines, "commit.merged", commits.merged());
        add(lines, "commit.intent", commits.intents());
        add(lines, "commit.dropped", commits.dropped());
        add(lines, "commit.retried", commits.retried());
        add(lines, "commit.refused_full", commits.refusedFull());
        add(lines, "commit.unplanned", commits.unplanned());
        add(lines, "commit.order_violations", commits.orderViolations());
        add(lines, "commit.first_divergence_position", commits.firstDivergencePosition());
        add(lines, "commit.plan_order_matches", commits.orderViolations() == 0L ? 1 : 0);
        add(lines, "commit.ticks", commits.ticks());
        add(lines, "commit.replay_logged_steps", replay == null ? 0 : replay.loggedSteps());
        add(lines, "commit.replay_log_digest", replay == null ? 0L
            : Long.toHexString(replay.logDigest()));
        add(lines, "commit.replay_order_matches", replay == null ? -1
            : (replay.orderMatches() ? 1 : 0));
        for (CommitRing ring : commits.rings()) {
            String prefix = "commit.ring." + safe(ring.worldId()) + "." + safe(ring.domainId()) + ".";
            add(lines, prefix + "depth", ring.depth());
            add(lines, prefix + "capacity", ring.capacity());
            add(lines, prefix + "pushed", ring.pushed());
            add(lines, prefix + "popped", ring.popped());
            add(lines, prefix + "refused_full", ring.refusedFull());
        }
    }

    private static String joinIds(List<Long> ids) {
        if (ids.isEmpty()) {
            return "-";
        }
        StringBuilder builder = new StringBuilder();
        for (Long id : ids) {
            if (builder.length() > 0) {
                builder.append(",");
            }
            builder.append(id);
        }
        return builder.toString();
    }

    private static String commitOrderText(TickPlan plan) {
        if (plan.commitOrder().isEmpty()) {
            return "-";
        }
        StringBuilder builder = new StringBuilder();
        for (TickPlan.CommitStep step : plan.commitOrder()) {
            if (builder.length() > 0) {
                builder.append(",");
            }
            builder.append(step.position()).append(":").append(safe(step.worldId())).append("/")
                .append(safe(step.domainId())).append(step.intent() ? "#intent" : "");
        }
        return builder.toString();
    }

    private static String modesText(TickPlan plan) {
        if (plan.domainModes().isEmpty()) {
            return "-";
        }
        StringBuilder builder = new StringBuilder();
        for (TickPlan.DomainMode mode : plan.domainModes()) {
            if (builder.length() > 0) {
                builder.append(",");
            }
            builder.append(safe(mode.worldId())).append("/").append(safe(mode.domainId()))
                .append("=").append(mode.mode().name().toLowerCase(Locale.ROOT))
                .append(":").append(mode.reason().name().toLowerCase(Locale.ROOT));
        }
        return builder.toString();
    }

    /** The two states of the budget and the four conditions the control plane judges them by. */
    private static void budgetStates(List<String> lines, KernelModule module) {
        BudgetStateMachine.Decision decision = module.budgetState();
        BudgetStateMachine machine = module.budgetStates();
        add(lines, "budget.state.observation_only", 1);
        add(lines, "budget.state.phase", BudgetStateMachine.key(machine.phase()));
        add(lines, "budget.state.reason", decision == null ? "unplanned" : decision.reason());
        add(lines, "budget.state.margins_within", decision == null ? -1
            : (decision.marginsWithin() ? 1 : 0));
        add(lines, "budget.state.wait_bound_clean", decision == null ? -1
            : (decision.waitBoundClean() ? 1 : 0));
        add(lines, "budget.state.reserve_within", decision == null ? -1
            : (decision.reserveWithin() ? 1 : 0));
        add(lines, "budget.state.conservation_holds", decision == null ? -1
            : (decision.conservationHolds() ? 1 : 0));
        add(lines, "budget.state.overrun_hits", decision == null ? 0L : decision.overrunHits());
        add(lines, "budget.state.entered_tick", decision == null ? 0L : decision.enteredTick());
        add(lines, "budget.state.normal_ticks", decision == null ? 0L : decision.normalTicks());
        add(lines, "budget.state.degraded_ticks", decision == null ? 0L : decision.degradedTicks());
        add(lines, "budget.state.entered", decision == null ? 0L : decision.enteredCount());
        add(lines, "budget.state.left", decision == null ? 0L : decision.leftCount());
    }

    /** The five rungs of the resource ladder: the four items each carries, the three counters and
     * the return gate. */
    private static void degradation(List<String> lines, KernelModule module) {
        DegradeLadder ladder = module.ladder();
        DegradeLadder.Sign sign = ladder.sign();
        add(lines, "degrade.observation_only", 1);
        add(lines, "degrade.levels", ladder.rungs().size());
        add(lines, "degrade.actions_enabled", sign.actionsEnabled() ? 1 : 0);
        add(lines, "degrade.rollback_ticks", KernelSettings.degradeRollbackTicks());
        add(lines, "degrade.deepest", sign.deepest().name().toLowerCase(Locale.ROOT));
        add(lines, "degrade.entered_tick", sign.enteredTick());
        add(lines, "degrade.ticks_observed", sign.ticksObserved());
        add(lines, "degrade.ticks_clean", sign.cleanTicks());
        add(lines, "degrade.skipped_total", sign.skippedCount());
        add(lines, "degrade.entered_total", ladder.enteredTotal());
        add(lines, "degrade.effective_total", ladder.effectiveTotal());
        add(lines, "degrade.returned_total", ladder.returnedTotal());
        add(lines, "degrade.effective_zero",
            ladder.enteredTotal() > 0L && ladder.effectiveTotal() == 0L ? 1 : 0);
        for (DegradeLadder.Rung rung : ladder.rungs()) {
            String prefix = "degrade.row." + key(rung.level()) + ".";
            add(lines, prefix + "code", rung.code().text());
            add(lines, prefix + "trigger", rung.trigger());
            add(lines, prefix + "action", rung.action());
            add(lines, prefix + "signal", rung.signal());
            add(lines, prefix + "return", rung.returnCondition());
        }
        for (DegradeLadder.Counters counters : ladder.counters()) {
            String prefix = "degrade.row." + key(counters.level()) + ".";
            add(lines, prefix + "entered", counters.entered());
            add(lines, prefix + "effective", counters.effective());
            add(lines, prefix + "returned", counters.returned());
            add(lines, prefix + "in_force", counters.level() == sign.deepest() ? 1 : 0);
        }
        for (DegradeLadder.Rung rung : ladder.rungs()) {
            DegradeLadder.Gate gate = ladder.gate(rung.level(), KernelSettings.degradeRollbackTicks());
            String prefix = "degrade.gate." + key(rung.level()) + ".";
            add(lines, prefix + "window_ticks", gate.windowTicks());
            add(lines, prefix + "clean_ticks", gate.cleanTicks());
            add(lines, prefix + "second_bound", gate.secondBound() ? 1 : 0);
            add(lines, prefix + "second_source", gate.secondSource());
            add(lines, prefix + "second_satisfied", gate.secondSatisfied() ? 1 : 0);
            add(lines, prefix + "ready", gate.ready() ? 1 : 0);
            add(lines, prefix + "blocked_by", gate.blockedBy());
        }
        add(lines, "degrade.skipping_code", RejectCode.TICK_BUDGET_EXHAUSTED.text());
        for (RejectCode code : ladderCodes(ladder)) {
            add(lines, "degrade.code." + code.text(), ladder.codeCount(code));
        }
    }

    private static Set<RejectCode> ladderCodes(DegradeLadder ladder) {
        Set<RejectCode> codes = new LinkedHashSet<>();
        for (DegradeLadder.Rung rung : ladder.rungs()) {
            codes.add(rung.code());
        }
        codes.add(RejectCode.TICK_BUDGET_EXHAUSTED);
        return codes;
    }

    private static String key(DegradeLevel level) {
        return level == null ? "none" : level.name().toLowerCase(Locale.ROOT);
    }

    private static String selfKeys(List<SelfClass> classes) {
        List<String> keys = new ArrayList<>(classes.size());
        for (SelfClass selfClass : classes) {
            keys.add(selfClass.key());
        }
        return join(keys);
    }

    private static String classKeys(List<ShareClass> classes) {
        List<String> keys = new ArrayList<>(classes.size());
        for (ShareClass shareClass : classes) {
            keys.add(shareClass.key());
        }
        return join(keys);
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

    /** The fields the domains contribute come through the kernel's own formatting, so a domain names
     * its fields and the readout keeps one shape. */
    private static void domainReadings(List<String> lines, KernelModule module) {
        DomainReadings sink = new DomainReadings() {

            @Override
            public void add(String name, double value) {
                KernelReadings.add(lines, name, format(value));
            }

            @Override
            public void add(String name, long value) {
                KernelReadings.add(lines, name, value);
            }
        };
        for (KernelDomain domain : module.domains()) {
            domain.readings(sink);
        }
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
        waitContract(lines, module);
    }

    /** The dependency-contract side of the registry: the nine rows the contract writes down, the four
     * items each row carries, the signal reading each row publishes and the bound-action gate. These
     * fields are observation requests; no verdict reads them. */
    private static void waitContract(List<String> lines, KernelModule module) {
        WaitPointRegistry registry = module.waitPoints();
        WaitPointRegistry.NineRows nine = registry.nineRows();
        add(lines, "wp.observation_only", 1);
        add(lines, "wp.nine_rows", nine.rows());
        add(lines, "wp.nine_rows_contract", nine.contractRows());
        add(lines, "wp.nine_rows_aligned", nine.aligned() ? 1 : 0);
        add(lines, "wp.nine_rows_unserved", join(nine.unserved()));
        add(lines, "wp.nine_rows_appended", join(nine.appended()));
        for (WaitPointRegistry.WaitPointEntry row : registry.rows()) {
            String prefix = "wp.row." + safe(row.wpId()) + ".";
            add(lines, prefix + "key", row.wpId());
            add(lines, prefix + "producer", row.producer());
            add(lines, prefix + "signal_kind", row.signal().kind().name());
            add(lines, prefix + "signal_field", row.signal().fieldRef());
            add(lines, prefix + "timeout_action", row.timeoutAction());
            add(lines, prefix + "degrade_to", row.degradeTo());
            add(lines, prefix + "overrun", registry.overrunOf(row.wpId()));
        }
        for (WaitProgress.Reading reading : registry.progress().readings()) {
            String prefix = "wp.signal." + safe(reading.wpId()) + ".";
            add(lines, prefix + "kind", reading.kind().name());
            add(lines, prefix + "bound", reading.bound() ? 1 : 0);
            add(lines, prefix + "source", safe(reading.source()));
            add(lines, prefix + "value", reading.value());
            add(lines, prefix + "delta", reading.delta());
        }
        add(lines, "wp.signal_rows", registry.progress().declaredCount());
        add(lines, "wp.signal_bound", registry.progress().boundCount());
        add(lines, "wait.refuse_unregistered", KernelSettings.refuseUnregisteredWaits() ? 1 : 0);
        add(lines, "wait.refused_unregistered", registry.refusedUnregistered());
        ForcedConvergence gate = registry.convergence();
        add(lines, "wp.convergence_reached", gate.reached());
        add(lines, "wp.convergence_effective", gate.effective());
        ForcedConvergence.Rollback rollback =
            gate.rollback(ForcedConvergence.ROLLBACK_WINDOW_TICKS);
        add(lines, "wp.rollback_window_ticks", rollback.windowTicks());
        add(lines, "wp.rollback_ticks_observed", rollback.ticksObserved());
        add(lines, "wp.rollback_ticks_clean", rollback.ticksClean());
        add(lines, "wp.rollback_ready", rollback.ready() ? 1 : 0);
    }

    private static void waitSiteReadings(List<String> lines, KernelModule module) {
        WaitSiteReadings readings = module.waitSites().readings();
        add(lines, "wait.over_one_tick", readings.overOneTickTotal());
        add(lines, "wait.forced_convergence_candidates", readings.convergenceCandidateTotal());
        for (int index = 0; index < readings.siteCount(); index++) {
            String siteId = safe(readings.siteIds()[index]);
            String prefix = "wait.site." + siteId + ".";
            add(lines, "wait.observed_by_site." + siteId, readings.observed(index));
            add(lines, "wait.ms_by_site." + siteId,
                format(readings.observedNanos(index) / 1_000_000.0));
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

    /** The safety net: the five kinds on both dimensions, the cascade of the newest window and the
     * zero-effect verdict of every rung. Every field is an observation request; no gate reads one. */
    private static void safety(List<String> lines, KernelModule module) {
        SafetyNet net = module.safety();
        ZeroEffectDetector zeroEffect = module.zeroEffect();
        SafetyNet.Cascade cascade = net.cascade();
        add(lines, "safety.observation_only", 1);
        add(lines, "safety.enabled", KernelSettings.safetyNet() ? 1 : 0);
        add(lines, "safety.kinds", SafetyNet.ViolationKind.kindCount());
        add(lines, "safety.escalation_switch", net.switchEnabled() ? 1 : 0);
        add(lines, "safety.escalation_off_by_default", KernelSettings.safetyDegrade() ? 0 : 1);
        add(lines, "safety.cascade_cap", cascade.cap());
        add(lines, "safety.cascade_depth", cascade.depth());
        add(lines, "safety.cascade_steps", cascade.steps());
        add(lines, "safety.cascade_capped", cascade.capped());
        add(lines, "safety.cascade_stopped", cascade.stopped());
        add(lines, "safety.violations_total", net.total());
        add(lines, "safety.evidence_empty", net.evidenceEmpty());
        add(lines, "safety.escalated", net.escalated());
        for (SafetyNet.KindCounts counts : net.kinds()) {
            SafetyNet.ViolationKind kind = SafetyNet.ViolationKind.of(counts.kind());
            String prefix = "safety.kind." + counts.kind() + ".";
            add(lines, prefix + "code", kind == null ? "none" : kind.code().text());
            add(lines, prefix + "detector", kind == null ? "none" : kind.detector());
            add(lines, prefix + "total", counts.total());
            add(lines, prefix + "escalated", counts.escalated());
            for (SafetyNet.Cell cell : net.sites(kind)) {
                add(lines, prefix + "site." + safe(cell.key()), cell.count());
            }
            for (SafetyNet.Cell cell : net.worlds(kind)) {
                add(lines, prefix + "world." + safe(cell.key()), cell.count());
            }
        }
        add(lines, "safety.zero_effect.window_ticks", KernelSettings.safetyZeroEffectTicks());
        add(lines, "safety.zero_effect.detected", zeroEffect.zeroEffectTotal());
        add(lines, "safety.zero_effect.changed", zeroEffect.changedTotal());
        add(lines, "safety.zero_effect.unproven", zeroEffect.unprovenTotal());
        add(lines, "safety.zero_effect.pending", zeroEffect.pending());
        add(lines, "safety.zero_effect.last_states", join(zeroEffect.lastStates()));
        add(lines, "safety.zero_effect.criterion",
            "zero_effect := entered>0 and effective>0 and metric(window)-metric(entry)==0");
    }

    /** The two exits: the frame each published last, the fields they share and the two guards that
     * keep them apart. Every field is an observation request; no gate reads one. */
    private static void exits(List<String> lines, KernelModule module) {
        DualExits exits = module.exits();
        DualExits.Frame control = exits.control();
        DualExits.Frame judgement = exits.judgement();
        DualExits.SameSource same = exits.sameSource();
        add(lines, "exit.observation_only", 1);
        add(lines, "exit.enabled", KernelSettings.dualExits() ? 1 : 0);
        add(lines, "exit.window_ticks", exits.windowTicks());
        add(lines, "exit.control_frames", exits.controlFrames());
        add(lines, "exit.judgement_frames", exits.judgementFrames());
        add(lines, "exit.control.complete", control.complete() ? 1 : 0);
        add(lines, "exit.control.tick", control.tickIndex());
        add(lines, "exit.control.missing", join(control.missing()));
        for (DualExits.Reading reading : control.readings()) {
            add(lines, "exit.control." + reading.name(), reading.text());
            add(lines, "exit.control." + reading.name() + ".origin", reading.origin());
            add(lines, "exit.control." + reading.name() + ".group",
                reading.group().name().toLowerCase(Locale.ROOT));
        }
        add(lines, "exit.judgement.complete", judgement.complete() ? 1 : 0);
        add(lines, "exit.judgement.tick", judgement.tickIndex());
        add(lines, "exit.judgement.first_tick", judgement.firstTick());
        add(lines, "exit.judgement.window_ticks", judgement.windowTicks());
        add(lines, "exit.judgement.missing", join(judgement.missing()));
        for (DualExits.Reading reading : judgement.readings()) {
            add(lines, "exit.judgement." + reading.name(), reading.text());
        }
        for (DualExits.Reading reading : judgement.tail()) {
            add(lines, "exit.judgement.tail." + reading.name(), reading.text());
        }
        add(lines, "exit.same_source.control_tick", same.controlTick());
        add(lines, "exit.same_source.judgement_tick", same.judgementTick());
        add(lines, "exit.same_source.fields", same.fields());
        add(lines, "exit.same_source.same_origin", same.sameOrigin());
        add(lines, "exit.same_source.same_value", same.sameValue());
        add(lines, "exit.same_source.equal", same.equal() ? 1 : 0);
        for (DualExits.CrossCheck check : same.checks()) {
            String prefix = "exit.same_source." + check.name() + ".";
            add(lines, prefix + "origin", check.origin());
            add(lines, prefix + "control", check.control());
            add(lines, prefix + "judgement", check.judgement());
            add(lines, prefix + "equal", check.equal() ? 1 : 0);
        }
        for (Map.Entry<String, Boolean> group : exits.groups().entrySet()) {
            add(lines, "exit.group." + group.getKey(), group.getValue() ? 1 : 0);
        }
        add(lines, "exit.control_window_feeds", exits.controlWindowFeeds());
        add(lines, "exit.judgement_write_dependencies", exits.judgementWriteDependencies());
        add(lines, "exit.missing_total", exits.missingTotal());
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
        add(lines, "control.degrade_entered_tick", frame.degradeEnteredTick());
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
