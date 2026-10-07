/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.kernel.KernelModule;
import io.izzel.arclight.common.prts.kernel.OwnerGrantPoint;
import io.izzel.arclight.common.prts.kernel.auth.OwnerRegistry;
import io.izzel.arclight.common.prts.kernel.auth.WriteLedger;
import io.izzel.arclight.common.prts.kernel.auth.WriteVersionSlots;
import io.izzel.arclight.common.prts.kernel.codes.DegradeLevel;
import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import io.izzel.arclight.common.prts.kernel.codes.RejectTrigger;
import io.izzel.arclight.common.prts.kernel.commit.CommitLog;
import io.izzel.arclight.common.prts.kernel.commit.CommitRing;
import io.izzel.arclight.common.prts.kernel.config.KernelSettings;
import io.izzel.arclight.common.prts.support.PrtsChunkFlow;
import io.izzel.arclight.common.prts.support.PrtsLoadProbe;
import io.izzel.arclight.common.prts.support.PrtsPipelineRows;
import io.izzel.arclight.common.prts.kernel.jobs.JobDeclaration;
import io.izzel.arclight.common.prts.kernel.jobs.JobGraph;
import io.izzel.arclight.common.prts.kernel.jobs.JobScheduler;
import io.izzel.arclight.common.prts.kernel.jobs.PipelineRoundJobs;
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
import io.izzel.arclight.common.prts.kernel.sites.WriteControlledSlots;
import io.izzel.arclight.common.prts.kernel.sites.WritePathCounters;
import io.izzel.arclight.common.prts.kernel.sites.WorldWriteGuard;
import io.izzel.arclight.common.prts.kernel.auth.HolderKind;
import io.izzel.arclight.common.prts.kernel.codes.WriteDisposition;
import io.izzel.arclight.common.prts.kernel.sites.ThreadOrigin;
import io.izzel.arclight.common.prts.kernel.arena.ArenaLedger;
import io.izzel.arclight.common.prts.kernel.arena.ArenaPassthrough;
import io.izzel.arclight.common.prts.kernel.diff.DiffProbe;
import io.izzel.arclight.common.prts.kernel.waitpoints.CoverageReport;
import io.izzel.arclight.common.prts.kernel.waitpoints.SiteInventory.WaitClass;
import io.izzel.arclight.common.prts.kernel.waitpoints.WaitLadder;
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
        arena(lines, module);
        rejectCodes(lines, module);
        shareBudget(lines, module);
        budgetStates(lines, module);
        degradation(lines, module);
        selfTimers(lines, module);
        domainReadings(lines, module);
        waitPoints(lines, module);
        waitLadderReadings(lines, module);
        differential(lines, module);
        contractLayers(lines, module);
        safety(lines, module);
        exits(lines, module);
        pipelineRows(lines, module);
        pipelineJobs(lines, module);
        tickDigest(lines, module);
        arrivalDigest(lines, module);
        loadObservation(lines, module);
        stallAttribution(lines, module);
        chunkDemand(lines, module);
        regions(lines, module);
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
        WriteVersionSlots versions = module.versionSlots();
        add(lines, "write.version_slots.enabled", versions.enabled() ? 1 : 0);
        add(lines, "write.version_slots.active", versions.activeSlots());
        add(lines, "write.version_slots.granted", versions.grantedCount());
        add(lines, "write.version_slots.advanced", versions.advancedCount());
        add(lines, "write.version_slots.world_drops", versions.worldDropCount());
        add(lines, "write.version_slots.reclaimed", versions.reclaimedCount());
        add(lines, "write.version_slots.carried", versions.carriedCount());
        add(lines, "write.version_slots.not_carried", versions.notCarriedCount());
        add(lines, "write.path_accounting_in_flight", counters.inFlightAttempts());
        controlledSlots(lines, module);
    }

    /** The claim face of the controlled write channel. The three verdicts are published next to the
     * landing face they must not be confused with: a captured write is a claim the decision point
     * took, not a write that landed, so captured, landed and the commit entries stay three fields. */
    private static void controlledSlots(List<String> lines, KernelModule module) {
        WriteControlledSlots slots = module.controlledSlots();
        add(lines, "write.controlled.enabled", slots.enabled() ? 1 : 0);
        add(lines, "write.controlled.slots", slots.slots());
        add(lines, "write.controlled.registered", slots.registeredCount());
        add(lines, "write.controlled.renewed", slots.renewedCount());
        add(lines, "write.controlled.advanced", slots.advancedCount());
        add(lines, "write.controlled.refused", slots.refusedCount());
        add(lines, "write.controlled.refused.wildcard",
            slots.refusedCount(WriteControlledSlots.Refusal.WILDCARD_KEY));
        add(lines, "write.controlled.refused.empty_key",
            slots.refusedCount(WriteControlledSlots.Refusal.EMPTY_KEY));
        add(lines, "write.controlled.refused.empty_write_set",
            slots.refusedCount(WriteControlledSlots.Refusal.EMPTY_WRITE_SET));
        add(lines, "write.controlled.refused.plan_sequence",
            slots.refusedCount(WriteControlledSlots.Refusal.PLAN_SEQUENCE_INVALID));
        add(lines, "write.controlled.refused.duplicate",
            slots.refusedCount(WriteControlledSlots.Refusal.DUPLICATE_KEY));
        add(lines, "write.controlled.attempts", slots.attemptsCount());
        add(lines, "write.controlled.default_path", slots.defaultPathCount());
        add(lines, "write.controlled.captured", slots.capturedCount());
        add(lines, "write.controlled.rejected", slots.rejectedCount());
        add(lines, "write.controlled.not_executable", slots.notExecutableCount());
        add(lines, "write.controlled.conservation_ok", slots.conservationHolds() ? 1 : 0);
        add(lines, "write.controlled.landed", module.commits().sequence());
        WriteControlledSlots.Decision decision = slots.lastDecision();
        add(lines, "write.controlled.last.verdict", decision == null ? "none"
            : decision.verdict().name().toLowerCase(Locale.ROOT));
        add(lines, "write.controlled.last.reason", decision == null ? "none" : decision.reason());
        add(lines, "write.controlled.last.world", decision == null || decision.key() == null ? "none"
            : decision.key().worldId());
        add(lines, "write.controlled.last.domain",
            decision == null || decision.key() == null ? "none" : decision.key().domainId());
        add(lines, "write.controlled.last.level", decision == null || decision.key() == null ? "none"
            : decision.key().level().name().toLowerCase(Locale.ROOT));
        add(lines, "write.controlled.last.segment",
            decision == null || decision.key() == null ? "none" : decision.key().segment());
        add(lines, "write.controlled.last.plan_sequence", decision == null ? 0L
            : decision.planSequence());
        add(lines, "write.controlled.last.node_key", decision == null ? "none"
            : decision.planNodeKey());
        add(lines, "write.controlled.last.position", decision == null ? -1 : decision.position());
        add(lines, "write.controlled.last.write_set_digest", decision == null ? "none"
            : decision.writeSetDigest());
        WriteControlledSlots.WriteRefusal refusal = slots.lastRefusal();
        add(lines, "write.controlled.last_refusal.reason", refusal == null ? "none"
            : refusal.reason());
        add(lines, "write.controlled.last_refusal.site", refusal == null ? "none" : refusal.siteId());
        add(lines, "write.controlled.last_refusal.holder", refusal == null ? "none"
            : refusal.holderKind().name().toLowerCase(Locale.ROOT));
        add(lines, "write.controlled.last_refusal.world", refusal == null ? "none"
            : refusal.worldId());
        add(lines, "write.controlled.last_refusal.domain", refusal == null ? "none"
            : refusal.domainId());
        add(lines, "write.controlled.last_refusal.level", refusal == null ? "none"
            : refusal.level().name().toLowerCase(Locale.ROOT));
        add(lines, "write.controlled.last_refusal.segment", refusal == null ? "none"
            : refusal.segment());
        add(lines, "write.controlled.last_refusal.plan_sequence", refusal == null ? 0L
            : refusal.planSequence());
        add(lines, "write.controlled.last_refusal.node_key", refusal == null ? "none"
            : refusal.planNodeKey());
        add(lines, "write.controlled.last_refusal.tick", refusal == null ? 0L : refusal.tickIndex());
        add(lines, "write.controlled.last_refusal.expected_version", refusal == null ? 0L
            : refusal.expectedVersion());
        add(lines, "write.controlled.last_refusal.carried_version", refusal == null ? 0L
            : refusal.carriedVersion());
        add(lines, "write.controlled.last_refusal.world_epoch", refusal == null ? 0L
            : refusal.worldEpoch());
        add(lines, "write.controlled.last_refusal.slot_generation", refusal == null ? 0L
            : refusal.slotGeneration());
        add(lines, "write.controlled.last_refusal.write_set_digest", refusal == null ? "none"
            : refusal.writeSetDigest());
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
        OwnerGrantPoint grants = module.ownerGrants();
        add(lines, "token.active", owners.activeTokens());
        add(lines, "token.acquired", owners.acquiredCount());
        add(lines, "token.reacquired", owners.reacquiredCount());
        add(lines, "token.released", owners.releasedCount());
        add(lines, "token.release_refused", owners.releaseRefusedCount());
        add(lines, "token.expired_reclaimed", owners.expiredReclaimedCount());
        add(lines, "token.double_holder", owners.doubleHolderCount());
        add(lines, "token.reclaim_passes", owners.reclaimPasses());
        add(lines, "token.conservation_ok", owners.conservationHolds() ? 1 : 0);
        add(lines, "token.grants_enabled", grants.enabled() ? 1 : 0);
        add(lines, "token.declared", grants.declaredCount());
        add(lines, "token.granted", grants.grantedCount());
        add(lines, "token.released_by_plan", grants.releasedByPlanCount());
        add(lines, "token.held_by_declarations", grants.heldCount());
        add(lines, "token.refused", grants.refusedCount());
        add(lines, "token.refused.cross_world", grants.refusedCrossWorldCount());
        add(lines, "token.refused.expired", grants.refusedExpiredCount());
        add(lines, "token.refused.no_version", grants.refusedNoVersionCount());
        add(lines, "token.refused.double_holder", grants.refusedDoubleHolderCount());
        add(lines, "token.refused.undeclared", grants.refusedUndeclaredCount());
        RejectTrigger.Diag5 refusal = grants.lastRefusal();
        add(lines, "token.last_refusal.code", refusal == null ? "none" : refusal.code());
        add(lines, "token.last_refusal.trigger", grants.lastRefusalTrigger() == null ? "none"
            : grants.lastRefusalTrigger().name());
        add(lines, "token.last_refusal.site", refusal == null ? "none" : refusal.siteId());
        add(lines, "token.last_refusal.thread", refusal == null ? "none" : refusal.threadRef());
        add(lines, "token.last_refusal.world", refusal == null ? "none" : refusal.worldId());
        add(lines, "token.last_refusal.tick", refusal == null ? 0L : refusal.tickIndex());
        add(lines, "token.last_refusal.domain", grants.lastRefusedDomain().isEmpty() ? "none"
            : grants.lastRefusedDomain());
    }

    /** The arena face: the pin and release pairs of the slots, and the two reserved shapes that
     * carry what the model does not name. Every field is an observation request; no gate reads one. */
    private static void arena(List<String> lines, KernelModule module) {
        ArenaLedger ledger = module.arena();
        ArenaPassthrough passthrough = module.passthrough();
        ArenaPassthrough.Reading reading = passthrough.reading();
        add(lines, "arena.observation_only", 1);
        add(lines, "arena.claims", ledger.claims());
        add(lines, "arena.releases", ledger.releases());
        add(lines, "arena.pinned", ledger.pinnedCount());
        add(lines, "arena.generation_bumps", ledger.generationBumps());
        add(lines, "arena.foreign_writes", ledger.foreignWrites());
        add(lines, "arena.refusals", ledger.refusals());
        add(lines, "arena.stale_releases", ledger.staleReleases());
        add(lines, "arena.repeat_releases", ledger.repeatReleases());
        add(lines, "arena.quarantined_slots", ledger.quarantinedSlots());
        add(lines, "arena.pin_pairs_hold", ledger.pinPairsHold() ? 1 : 0);
        add(lines, "arena.slots", reading.slots());
        add(lines, "arena.slots_held", reading.held());
        add(lines, "arena.passthrough_written", reading.written());
        add(lines, "arena.passthrough_read", reading.read());
        add(lines, "arena.passthrough_lost", reading.lost());
        add(lines, "arena.roundtrip_pairs", reading.roundtripPairs());
        add(lines, "arena.roundtrip_equal", reading.roundtripEqual());
        add(lines, "arena.roundtrip_diff", reading.roundtripDiff());
        add(lines, "arena.version_checks", reading.versionChecks());
        add(lines, "arena.version_publishes", reading.versionPublishes());
        add(lines, "arena.version_mismatch", reading.versionMismatch());
        add(lines, "arena.bytes_kept", reading.bytesKept());
    }

    /** The differential of the two arms: how many tick pairs were compared and how many agreed, the
     * first fork down to one row and one field, and what the comparison could not place. Every field
     * is an observation request; no gate reads one. */
    private static void differential(List<String> lines, KernelModule module) {
        DiffProbe.DiffReport report = module.arms().report();
        add(lines, "diff.observation_only", 1);
        add(lines, "diff.arms", 2);
        add(lines, "diff.tick_pairs", report.tickPairs());
        add(lines, "diff.equal", report.equal());
        add(lines, "diff.rate", format(report.rate()));
        add(lines, "diff.algorithm", report.algorithmId().isEmpty() ? "none" : report.algorithmId());
        add(lines, "diff.unattributed", report.unattributed());
        add(lines, "diff.first_fork_tick", report.firstForkTick());
        add(lines, "diff.first_fork_world", report.firstForkWorld().isEmpty() ? "none"
            : safe(report.firstForkWorld()));
        add(lines, "diff.first_fork_region", report.firstForkRegion().isEmpty() ? "none"
            : safe(report.firstForkRegion()));
        add(lines, "diff.first_fork_batch", report.firstForkBatch());
        add(lines, "diff.first_fork_entity", report.firstForkEntityId());
        add(lines, "diff.first_fork_host_ordinal", report.firstForkHostOrdinal());
        add(lines, "diff.first_fork_field", report.firstForkField().isEmpty() ? "none"
            : report.firstForkField());
        add(lines, "diff.forked_fields", report.fieldCount());
        add(lines, "diff.attributed_sites", report.attributedSites());
        add(lines, "diff.located_rows", report.locatedRows());
    }

    /** The three rungs of the wait ladder: the four items each carries, the three counters whose
     * sum is the reached and effective total, and the return gate that needs a clean run and the
     * progress signal moving again. Every field is an observation request; no gate reads one. */
    private static void waitLadderReadings(List<String> lines, KernelModule module) {
        WaitLadder ladder = module.waitLadder();
        WaitLadder.Sign sign = ladder.sign();
        add(lines, "wait.ladder.observation_only", 1);
        add(lines, "wait.ladder.levels", ladder.rungs().size());
        add(lines, "wait.ladder.actions_enabled", sign.actionsEnabled() ? 1 : 0);
        add(lines, "wait.ladder.rollback_ticks", KernelSettings.waitRollbackTicks());
        add(lines, "wait.ladder.deepest", sign.deepest().name().toLowerCase(Locale.ROOT));
        add(lines, "wait.ladder.entered_tick", sign.enteredTick());
        add(lines, "wait.ladder.ticks_observed", sign.ticksObserved());
        add(lines, "wait.ladder.ticks_clean", sign.cleanTicks());
        add(lines, "wait.ladder.skipped_total", sign.skippedCount());
        add(lines, "wait.ladder.entered_total", ladder.enteredTotal());
        add(lines, "wait.ladder.effective_total", ladder.effectiveTotal());
        add(lines, "wait.ladder.returned_total", ladder.returnedTotal());
        for (WaitLadder.Rung rung : ladder.rungs()) {
            String prefix = "wait.ladder.row." + rung.level().name().toLowerCase(Locale.ROOT) + ".";
            add(lines, prefix + "code", rung.code().text());
            add(lines, prefix + "trigger", rung.trigger());
            add(lines, prefix + "action", rung.action());
            add(lines, prefix + "signal", rung.signal());
            add(lines, prefix + "return", rung.returnCondition());
        }
        for (WaitLadder.Counters counters : ladder.counters()) {
            String prefix = "wait.ladder.row." + counters.level().name().toLowerCase(Locale.ROOT)
                + ".";
            add(lines, prefix + "entered", counters.entered());
            add(lines, prefix + "effective", counters.effective());
            add(lines, prefix + "returned", counters.returned());
            add(lines, prefix + "in_force", counters.level() == sign.deepest() ? 1 : 0);
        }
        for (WaitLadder.Rung rung : ladder.rungs()) {
            WaitLadder.Gate gate = ladder.gate(rung.level(), KernelSettings.waitRollbackTicks());
            String prefix = "wait.gate." + rung.level().name().toLowerCase(Locale.ROOT) + ".";
            add(lines, prefix + "window_ticks", gate.windowTicks());
            add(lines, prefix + "clean_ticks", gate.cleanTicks());
            add(lines, prefix + "second_bound", gate.secondBound() ? 1 : 0);
            add(lines, prefix + "second_source", gate.secondSource());
            add(lines, prefix + "second_satisfied", gate.secondSatisfied() ? 1 : 0);
            add(lines, prefix + "ready", gate.ready() ? 1 : 0);
            add(lines, prefix + "blocked_by", gate.blockedBy());
        }
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
        add(lines, "plan.wallclock_reads", module.planClock().reads());
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
        add(lines, "plan.nanos_last", module.planNanosLast());
        add(lines, "plan.nanos_total", module.planNanosTotal());
        add(lines, "plan.nanos_max", module.planNanosMax());
        add(lines, "plan.nanos_builds", module.planNanosBuilds());
        add(lines, "plan.nanos_per_build", module.planNanosBuilds() == 0L ? 0L
            : module.planNanosTotal() / module.planNanosBuilds());
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
        add(lines, "jobs.declared", module.jobDeclarations());
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
        // The two class totals are the ones an acceptance line names; the per-row counters are
        // published beside them under their own prefix, because one row carries call sites of both
        // classes and a per-row total cannot say whether every class was walked.
        for (WaitClass waitClass : WaitClass.classified()) {
            add(lines, "wp.injection_walkthrough_" + waitClass.key(),
                registry.walkthroughOf(waitClass));
        }
        add(lines, "wp.injection_walkthrough_classes", WaitClass.classified().size());
        add(lines, "wp.injection_walkthrough_unclassified",
            registry.walkthroughOf(WaitClass.UNCLASSIFIED));
        for (Map.Entry<String, Integer> entry : coverage.injectionWalkthrough().entrySet()) {
            add(lines, "wp.walkthrough." + entry.getKey(), entry.getValue());
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
        chunkMaterialization(lines, module);
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

    /** The producer of one row's declared progress signal: the value it counted for the
     * materialized chunks of each world, the move since the previous export, the state the signal
     * is in, and what the producer refused to count. The markers the producer has to stamp - the
     * world, the generation status, the generation cycle and the tick - are read from the newest
     * completion it accepted. */
    private static void chunkMaterialization(List<String> lines, KernelModule module) {
        ChunkMaterializationObserver producer = module.chunkMaterialization();
        ChunkMaterializationObserver.Reading reading = producer.read();
        add(lines, "wp.progress.chunk.source", reading.source());
        add(lines, "wp.progress.chunk.bound", reading.bound() ? 1 : 0);
        add(lines, "wp.progress.chunk.value", reading.value());
        add(lines, "wp.progress.chunk.delta", reading.delta());
        add(lines, "wp.progress.chunk.state", reading.state().name());
        add(lines, "wp.progress.chunk.events", reading.events());
        add(lines, "wp.progress.chunk.duplicates", reading.duplicates());
        add(lines, "wp.progress.chunk.cross_world", reading.crossWorld());
        add(lines, "wp.progress.chunk.cross_tick", reading.crossTick());
        add(lines, "wp.progress.chunk.non_monotonic", reading.nonMonotonic());
        add(lines, "wp.progress.chunk.folded_ticks", producer.foldedTicks());
        add(lines, "wp.progress.chunk.violations", producer.violations().size());
        ChunkMaterializationObserver.Event last = producer.lastEvent();
        add(lines, "wp.progress.chunk.world", last == null ? "-" : last.worldId());
        add(lines, "wp.progress.chunk.status", last == null ? "-" : last.status());
        add(lines, "wp.progress.chunk.generation", last == null ? "-" : last.generation());
        add(lines, "wp.progress.chunk.tick", last == null ? "-" : last.tick());
        for (String world : producer.worlds()) {
            String prefix = "wp.progress.chunk.world." + safe(world) + ".";
            add(lines, prefix + "materialized", producer.worldTotal(world));
            for (Map.Entry<Long, Long> group : producer.tickGroups(world).entrySet()) {
                add(lines, prefix + "tick." + group.getKey(), group.getValue());
            }
        }
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
            // The two dimensions are read on the union of the sites and the worlds seen in this
            // run, so a kind with nothing on a column still gets the column and reads zero there.
            // The family of a site key is its attribution: the kernel's own, or the name of a
            // domain, and only the second makes the row evidence about that domain.
            List<SafetyNet.Cell> siteCells = net.readSite(kind);
            List<SafetyNet.Cell> worldCells = net.readWorld(kind);
            int attributed = 0;
            int siteZeros = 0;
            for (SafetyNet.Cell cell : siteCells) {
                add(lines, prefix + "site." + safe(cell.key()), cell.count());
                add(lines, prefix + "site." + safe(cell.key()) + ".domain",
                    SafetyNet.domainOf(cell.key()));
                attributed = attributed
                    + (net.isDomain(SafetyNet.domainOf(cell.key())) ? 1 : 0);
                siteZeros = siteZeros + (cell.count() == 0L ? 1 : 0);
            }
            int worldZeros = 0;
            for (SafetyNet.Cell cell : worldCells) {
                add(lines, prefix + "world." + safe(cell.key()), cell.count());
                add(lines, prefix + "world." + safe(cell.key()) + ".domain",
                    SafetyNet.domainOf(cell.key()));
                worldZeros = worldZeros + (cell.count() == 0L ? 1 : 0);
            }
            add(lines, prefix + "site_count", siteCells.size());
            add(lines, prefix + "site_zero_rows", siteZeros);
            add(lines, prefix + "world_count", worldCells.size());
            add(lines, prefix + "world_zero_rows", worldZeros);
            // The one dimensional row and the two dimensional rows have to recompute into each
            // other; the reading publishes both halves so the identity is checkable from the file.
            add(lines, prefix + "site_sum", net.siteBound(kind));
            add(lines, prefix + "world_sum", net.worldBound(kind));
            add(lines, prefix + "site_equals_total", net.siteBound(kind) == counts.total() ? 1 : 0);
            add(lines, prefix + "world_equals_total",
                net.worldBound(kind) == counts.total() ? 1 : 0);
            add(lines, prefix + "domain_rows", attributed);
            add(lines, prefix + "attribution",
                attributed > 0 ? "real-domain" : "no-domain-attribution");
        }
        add(lines, "safety.domains_declared", net.domains().size());
        add(lines, "safety.dimension_conservation_ok", net.conservationHolds() ? 1 : 0);
        add(lines, "safety.dimension_equations",
            "site_sum(kind) = world_sum(kind) = total(kind) for every kind");
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

    /** The jobs the chunk pipeline's own rounds were declared as: how many rounds were read, how
     * many declared nothing because no world was read for their mailbox, and which declaration each
     * node of the newest plan came from. A node of that domain the drain has no declaration for is
     * counted apart instead of being read as traced. */
    private static void pipelineJobs(List<String> lines, KernelModule module) {
        PipelineRoundJobs jobs = module.pipelineJobs();
        add(lines, "r02.enabled", KernelSettings.pipelineJobs() && KernelSettings.tickPlan() ? 1 : 0);
        add(lines, "r02.windows", jobs.windows());
        add(lines, "r02.rounds_seen", jobs.roundsSeen());
        add(lines, "r02.rounds_unplaced", jobs.roundsUnplaced());
        add(lines, "r02.tasks_seen", jobs.tasksSeen());
        add(lines, "r02.jobs_declared", jobs.declared());
        add(lines, "r02.jobs_refused", jobs.refused());
        add(lines, "r02.rounds_covered", jobs.coveredRounds());
        add(lines, "r02.last_declared", jobs.lastDeclared());
        add(lines, "r02.last_rounds", jobs.lastRounds());
        add(lines, "r02.dispatch_handed_out", module.lastDispatchHandedOut());
        TickPlan plan = module.plans().latest();
        List<String> traced = new ArrayList<>();
        int nodes = 0;
        if (plan != null && plan.graph() != null) {
            for (JobGraph.Node node : plan.graph().nodes()) {
                if (!PipelineRoundJobs.DOMAIN.equals(node.domainId())) {
                    continue;
                }
                nodes++;
                PipelineRoundJobs.Trace trace = jobs.traceOf(node.key());
                if (trace == null) {
                    continue;
                }
                traced.add("key=" + node.key() + "|world=" + trace.worldId() + "|mailbox="
                    + trace.mailbox() + "|rounds=" + trace.rounds() + "|handle=" + trace.handle()
                    + "|position=" + node.position() + "|batch_bound=" + node.batchBound()
                    + "|write_set=" + domainRefs(node.writeSet()) + "|owner_demand="
                    + ownerDemands(node.ownerDemands()));
            }
        }
        add(lines, "r02.plan_nodes", nodes);
        add(lines, "r02.nodes_traced", traced.size());
        add(lines, "r02.nodes_without_declaration", nodes - traced.size());
        for (int index = 0; index < traced.size(); index++) {
            add(lines, "r02.node." + (index + 1), traced.get(index));
        }
    }

    /** The write domains one node carries, each as its world, domain and level. */
    private static String domainRefs(List<JobDeclaration.DomainRef> refs) {
        StringBuilder text = new StringBuilder();
        for (JobDeclaration.DomainRef ref : refs) {
            if (text.length() > 0) {
                text.append(',');
            }
            text.append(ref.worldId()).append('/').append(ref.domainId()).append('@').append(ref.level());
        }
        return text.length() == 0 ? "-" : text.toString();
    }

    /** The write rights one node asks to hold, each as its level, holder and hold window. */
    private static String ownerDemands(List<JobDeclaration.OwnerDemand> demands) {
        StringBuilder text = new StringBuilder();
        for (JobDeclaration.OwnerDemand demand : demands) {
            if (text.length() > 0) {
                text.append(',');
            }
            text.append(demand.worldId()).append('/').append(demand.level()).append('/')
                .append(demand.domainId()).append('/').append(demand.holderKind()).append('/')
                .append(demand.holderSiteId()).append('@').append(demand.holdTicks());
        }
        return text.length() == 0 ? "-" : text.toString();
    }

    /** The chunk pipeline's own rows, counted where the pipeline runs them. Observation only: the
     * two class rows the batch interface of the chunk pipeline would carry, the widened host row
     * total they are read against, and the mailbox split behind them. */
    private static void pipelineRows(List<String> lines, KernelModule module) {
        PipelineRowObserver rows = module.pipelineRows();
        add(lines, "pipeline.row_tap_installed", PrtsPipelineRows.installed() ? 1 : 0);
        for (String bucket : PipelineRowObserver.buckets()) {
            add(lines, "pipeline.mailbox." + bucket + ".tasks", rows.tasks(bucket));
            add(lines, "pipeline.mailbox." + bucket + ".task_ms",
                format(rows.taskNanos(bucket) / 1_000_000.0));
            add(lines, "pipeline.mailbox." + bucket + ".rounds", rows.rounds(bucket));
        }
        add(lines, "pipeline.mailbox.tasks_total", rows.tasksTotal());
        add(lines, "pipeline.mailbox.rounds_total", rows.roundsTotal());
        long worldgen = rows.tasks(PipelineRowObserver.WORLDGEN);
        long light = rows.tasks(PipelineRowObserver.LIGHT);
        long writes = module.guard().counters().attemptsAt(WritePath.BLOCK_WRITE);
        add(lines, "pipeline.row.cls01", worldgen);
        add(lines, "pipeline.row.cls01_ms",
            format(rows.taskNanos(PipelineRowObserver.WORLDGEN) / 1_000_000.0));
        add(lines, "pipeline.row.cls03", light);
        add(lines, "pipeline.row.cls03_ms",
            format(rows.taskNanos(PipelineRowObserver.LIGHT) / 1_000_000.0));
        add(lines, "pipeline.row.cls02", writes);
        add(lines, "pipeline.row.widened_total", worldgen + light + writes);
    }

    /** The per tick digest of the chunk pipeline, one row per (tick, world, probe). The window is
     * off unless a process declares one, so a run that declares nothing exports the shape of the
     * face and no row. Every value below is a count or the exact bits of one, never a duration: a
     * digest that folded a schedule would differ between two runs of one scenario. */
    private static void tickDigest(List<String> lines, KernelModule module) {
        TickDigestObserver digest = module.tickDigest();
        add(lines, "digest.observation_only", 1);
        add(lines, "digest.armed", digest.armed() ? 1 : 0);
        add(lines, "digest.window_ticks", digest.windowTicks());
        add(lines, "digest.tap_installed", PrtsPipelineRows.ownerTapInstalled() ? 1 : 0);
        add(lines, "digest.probes", TickDigestObserver.PROBES.length);
        add(lines, "digest.probe_names", String.join(",", TickDigestObserver.PROBES));
        add(lines, "digest.worlds", digest.worlds().size());
        add(lines, "digest.worlds_list", digest.worlds().isEmpty() ? "none"
            : String.join(",", digest.worlds()));
        add(lines, "digest.ticks", digest.ticks());
        add(lines, "digest.ticks_folded", digest.foldedTicks());
        add(lines, "digest.rows", digest.rowsFolded());
        add(lines, "digest.placed_mailboxes", digest.placedMailboxes());
        add(lines, "digest.unplaced_rows", digest.unplacedRows());
        add(lines, "digest.other_mailbox_rows", digest.otherMailboxRows());
        add(lines, "digest.dropped_ticks", digest.droppedTicks());
        add(lines, "digest.deviated_rows", digest.deviatedRows());
        add(lines, "digest.first_tick", digest.firstTick());
        add(lines, "digest.last_tick", digest.lastTick());
        add(lines, "digest.dump_begin", 1);
        for (TickDigestObserver.Row row : digest.rows()) {
            lines.add("digest.row=" + row.tick() + "|" + row.world() + "|" + row.probe() + "|"
                + row.regionId() + "|" + row.probeIndex() + "|" + row.entitySeq() + "|"
                + bits(row.rows()) + "|" + bits(row.rounds()) + "|" + bits(row.rowsTotal()) + "|"
                + bits(row.roundsTotal()) + "|" + bits(row.ticksActive()) + "|"
                + bits(row.tickFirst()) + "|" + bits(row.rowsPeak()) + "|" + bits(row.seen()) + "|0|"
                + row.tick() + "|" + row.probeIndex() + "|" + Long.toHexString(row.value()) + "|"
                + row.algorithmId() + "|" + Long.toHexString(row.headerDigest()) + "|"
                + Long.toHexString(row.rowDigest()));
        }
        add(lines, "digest.dump_end", 1);
    }


    /** The per tick arrival face of the chunk pipeline and the write path, one row per (tick,
     * world). It is folded for the span the tick digest is armed for and exports the shape of the
     * face plus no row while no window is declared. Every value is a count of what arrived during
     * that tick, so two runs can be asked which ticks they were handed the same arrival on. */
    private static void arrivalDigest(List<String> lines, KernelModule module) {
        ArrivalDigestObserver arrival = module.arrivalDigest();
        add(lines, "arrival.observation_only", 1);
        add(lines, "arrival.armed", arrival.armed() ? 1 : 0);
        add(lines, "arrival.window_ticks", arrival.windowTicks());
        add(lines, "arrival.probe", ArrivalDigestObserver.PROBE);
        add(lines, "arrival.ticks", arrival.ticks());
        add(lines, "arrival.rows", arrival.rowsFolded());
        add(lines, "arrival.worlds", arrival.worlds().size());
        add(lines, "arrival.worlds_list", arrival.worlds().isEmpty() ? "none"
            : String.join(",", arrival.worlds()));
        add(lines, "arrival.first_tick", arrival.firstTick());
        add(lines, "arrival.last_tick", arrival.lastTick());
        add(lines, "arrival.placed_levels", arrival.placedLevels());
        add(lines, "arrival.writes_total", arrival.writesTotal());
        add(lines, "arrival.unplaced_writes", arrival.unplacedWrites());
        add(lines, "arrival.dump_begin", 1);
        for (ArrivalDigestObserver.Row row : arrival.rows()) {
            lines.add("arrival.row=" + row.tick() + "|" + row.world() + "|"
                + ArrivalDigestObserver.PROBE + "|" + "arrival" + "|0|" + row.requests() + "|"
                + bits(row.requests()) + "|" + bits(row.satisfied()) + "|" + bits(row.missed()) + "|"
                + bits(row.blocking()) + "|" + bits(row.futuresTaken()) + "|"
                + bits(row.futuresCompleted()) + "|" + bits(row.inFlight()) + "|"
                + bits(row.writes()) + "|0|" + row.tick() + "|0|" + Long.toHexString(row.value())
                + "|" + row.algorithmId() + "|" + Long.toHexString(row.headerDigest()) + "|"
                + Long.toHexString(row.rowDigest()));
        }
        add(lines, "arrival.dump_end", 1);
    }

    /** The exact bits of one digest value, so a reader on the other side of an export can rebuild
     *  the double the fold used instead of the six digits a decimal form would keep. */
    private static String bits(long value) {
        return Long.toHexString(Double.doubleToRawLongBits(value));
    }

    /** The regions the loaded chunks of every world fall into: the identity a stability window and
     *  a rollback gate are counted against, and the counters of the three actions this tree does not
     *  take. Every field is an observation request; no gate, verdict or release reads one. */
    private static void regions(List<String> lines, KernelModule module) {
        RegionIdentityObserver regions = module.regionIdentity();
        RegionIdentityObserver.Reading reading = regions.read(module.tickIndex());
        add(lines, "region.observation_only", 1);
        add(lines, "region.count", reading.count());
        add(lines, "region.identity_hash", reading.identityHash());
        add(lines, "region.stability_window_ticks", regions.stabilityWindowTicks());
        add(lines, "region.skip", regions.skip());
        add(lines, "region.rollback_gate_ticks", regions.rollbackGateTicks());
        add(lines, "region.barrier_wait_count", regions.barrierWaitCount());
        add(lines, "region.barrier_wait_source", RegionIdentityObserver.BARRIER_WAIT_SOURCE);
        add(lines, "region.source_installed", regions.installed() ? 1 : 0);
        add(lines, "region.partition_available", reading.available());
        add(lines, "region.worlds", reading.worlds());
        add(lines, "region.chunks", reading.chunks());
        add(lines, "region.partition_reads", reading.partitionReads());
        add(lines, "region.jitter_events", reading.jitterEvents());
        add(lines, "region.last_change_tick", reading.lastChangeTick());
        add(lines, "region.divisions_applied", regions.divisionsApplied());
        for (RegionIdentityObserver.WorldRow world : reading.perWorld()) {
            String prefix = "region.world." + safe(world.worldId()) + ".";
            add(lines, prefix + "components", world.components());
            add(lines, prefix + "chunks", world.chunks());
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


    /** The real-save observation face: the waits of the server thread inside and between ticks, the
     * mailbox flow of the chunk pipeline, the identity of the save and the counters that mean work
     * was dropped. Every field is an observation request; no gate, verdict or release reads one. */
    private static void loadObservation(List<String> lines, KernelModule module) {
        LoadThreadObserver watcher = module.loadThread();
        double interval = LoadThreadObserver.SAMPLE_INTERVAL_MILLIS;
        add(lines, "load.observation_only", 1);
        add(lines, "load.probe_installed", PrtsLoadProbe.installed() ? 1 : 0);
        add(lines, "load.sampler_running", watcher.installed() ? 1 : 0);
        add(lines, "load.sampler_interval_ms", format(interval));
        add(lines, "wait.park.thread", safe(watcher.threadName()));
        add(lines, "wait.park.samples", watcher.samples());
        add(lines, "wait.park.frames", watcher.parkFrames());
        add(lines, "wait.park.ms", format(watcher.parkFrames() * interval));
        add(lines, "wait.park.in_tick_frames", watcher.parkInTickFrames());
        add(lines, "wait.park.in_tick_ms", ms(watcher.parkInTickNanos()));
        add(lines, "wait.park.between_tick_frames", watcher.parkBetweenTickFrames());
        add(lines, "wait.park.between_tick_ms", ms(watcher.parkBetweenTickNanos()));
        add(lines, "wait.park.startup_frames", watcher.parkStartupFrames());
        add(lines, "wait.park.startup_ms", ms(watcher.parkStartupNanos()));
        add(lines, "wait.park.episodes", watcher.episodes());
        add(lines, "wait.park.max_episode_ms", ms(watcher.maxEpisodeNanos()));
        add(lines, "wait.dep.frames", watcher.dependencyFrames());
        add(lines, "wait.dep.ms", format(watcher.dependencyFrames() * interval));
        add(lines, "wait.queue.frames", watcher.queueFrames());
        add(lines, "wait.queue.ms", format(watcher.queueFrames() * interval));
        add(lines, "wait.lock.frames", watcher.lockFrames());
        add(lines, "wait.lock.ms", format(watcher.lockFrames() * interval));
        add(lines, "wait.other.frames", watcher.otherFrames());
        add(lines, "wait.other.ms", format(watcher.otherFrames() * interval));
        add(lines, "wait.axis.classify", "stack-signature");
        add(lines, "stall.threshold_ms", ms(LoadThreadObserver.STALL_TICK_NANOS));
        add(lines, "stall.windows", watcher.stallWindows());
        add(lines, "stall.ticks", watcher.stallTicks());
        add(lines, "stall.ms", ms(watcher.stallNanos()));
        add(lines, "stall.max_ms", ms(watcher.stallMaxNanos()));
        add(lines, "stall.frames", watcher.stallFrames());
        add(lines, "stall.entered_tick", watcher.stallEnteredTick());
        add(lines, "stall.in_window", watcher.stallInWindow() ? 1 : 0);
        add(lines, "tick.count", watcher.tickCount());
        add(lines, "tick.body_ms", ms(watcher.tickBodyNanos()));
        add(lines, "tick.cpu_ms", ms(watcher.tickCpuNanos()));
        add(lines, "tick.park_ms", ms(watcher.tickParkNanos()));
        add(lines, "tick.between_ms", ms(watcher.tickBetweenNanos()));
        add(lines, "tick.period_ms", ms(watcher.tickPeriodNanos()));
        add(lines, "tick.period_cpu_ms", ms(watcher.tickPeriodCpuNanos()));
        add(lines, "tick.unaccounted_ms", ms(watcher.tickUnaccountedNanos()));
        add(lines, "tick.period_ms_last", ms(watcher.lastPeriodWallNanos()));
        add(lines, "tick.unaccounted_ms_last", ms(watcher.lastUnaccountedNanos()));
        add(lines, "tick.cpu_supported", watcher.cpuSupported() ? 1 : 0);
        add(lines, "tick.body_ms_last", ms(watcher.lastBodyWallNanos()));
        add(lines, "tick.cpu_ms_last", ms(Math.max(0L, watcher.lastBodyCpuNanos())));
        add(lines, "tick.park_ms_last", ms(watcher.lastBodyParkNanos()));
        add(lines, "tick.between_ms_last", ms(watcher.lastBetweenNanos()));
        add(lines, "gc.collections", watcher.gcCollections());
        add(lines, "gc.time_ms", watcher.gcMillis());
        add(lines, "stall.busy.threshold_ms", ms(LoadThreadObserver.STALL_TICK_NANOS));
        add(lines, "stall.busy.park_share_max_pct", LoadThreadObserver.BUSY_PARK_SHARE_PERCENT);
        add(lines, "stall.busy.windows", watcher.busyWindows());
        add(lines, "stall.busy.ticks", watcher.busyTicks());
        add(lines, "stall.busy.ms", ms(watcher.busyNanos()));
        add(lines, "stall.busy.cpu_ms", ms(watcher.busyCpuNanos()));
        add(lines, "stall.busy.park_ms", ms(watcher.busyParkNanos()));
        add(lines, "stall.busy.offcpu_ms", ms(Math.max(0L,
            watcher.busyNanos() - watcher.busyCpuNanos() - watcher.busyParkNanos())));
        add(lines, "stall.busy.max_ms", ms(watcher.busyMaxNanos()));
        add(lines, "stall.busy.entered_tick", watcher.busyEnteredTick());
        add(lines, "stall.busy.in_window", watcher.busyInWindow() ? 1 : 0);
        add(lines, "stall.relation", "park-defined-stall-keys;" + "busy-adds-tick-bodies-over-the-bound"
            + "-whose-parked-share-is-below-" + LoadThreadObserver.BUSY_PARK_SHARE_PERCENT + "-pct");
        add(lines, "stall.watchdog.after_ms", ms(LoadThreadObserver.WATCHDOG_AFTER_NANOS));
        add(lines, "stall.watchdog.every_ms", ms(LoadThreadObserver.WATCHDOG_EVERY_NANOS));
        add(lines, "stall.watchdog.samples", watcher.watchdogSamples());
        add(lines, "stall.watchdog.top", safe(watcher.watchdogTop()));
        add(lines, "stall.watchdog.body_ms", ms(watcher.watchdogBodyNanos()));
        List<LoadThreadObserver.Watchdog> watchdogSamples = watcher.watchdogs();
        for (int index = 0; index < watchdogSamples.size(); index++) {
            LoadThreadObserver.Watchdog sample = watchdogSamples.get(index);
            add(lines, "stall.watchdog." + index + ".body_ms", ms(sample.bodyNanos()));
            add(lines, "stall.watchdog." + index + ".top", safe(sample.top()));
        }
        add(lines, "startup.first_tick_seen", watcher.firstTickSeen() ? 1 : 0);
        add(lines, "startup.ms", watcher.firstTickSeen()
            ? ms(watcher.firstTickNanos() - watcher.startedAtNanos()) : "0.000");
        add(lines, "startup.stalled", watcher.startupStalled() ? 1 : 0);
        long workerSamples = watcher.workerSamples();
        add(lines, "worker.samples", workerSamples);
        add(lines, "worker.busy_frames", watcher.workerBusyFrames());
        add(lines, "worker.busy_pct", workerSamples == 0L ? "0.000"
            : format(watcher.workerBusyFrames() * 100.0 / workerSamples));
        add(lines, "worker.threads", watcher.workerThreads());
        add(lines, "worker.threads_peak", watcher.workerThreadsPeak());
        add(lines, "worker.name_filter", "Worker");
        ChunkFlowObserver flow = module.chunkFlow();
        add(lines, "chunk.flow.installed", PrtsChunkFlow.installed() ? 1 : 0);
        add(lines, "chunk.flow.submitted", flow.submitted());
        add(lines, "chunk.flow.completed", flow.completed());
        add(lines, "chunk.flow.submitted_per_s", format(flow.submittedPerSecond()));
        add(lines, "chunk.flow.completed_per_s", format(flow.completedPerSecond()));
        add(lines, "chunk.flow.depth", flow.depth());
        add(lines, "chunk.flow.depth_peak", flow.depthPeak());
        add(lines, "chunk.flow.observed_ms", ms(flow.observedNanos()));
        add(lines, "chunk.flow.mailboxes", join(flow.mailboxes()));
        for (String mailbox : flow.mailboxes()) {
            String prefix = "chunk.flow.mailbox." + flow.key(mailbox) + ".";
            add(lines, prefix + "submitted", flow.submitted(mailbox));
            add(lines, prefix + "completed", flow.completed(mailbox));
            add(lines, prefix + "depth", flow.depth(mailbox));
            add(lines, prefix + "depth_peak", flow.depthPeak(mailbox));
        }
        SaveIdentityObserver save = module.saveIdentity();
        add(lines, "save.identity_installed", save.installed() ? 1 : 0);
        List<SaveIdentityObserver.World> worlds = save.worlds();
        add(lines, "save.worlds", worlds.size());
        add(lines, "save.level_dat_md5", save.levelDatMd5());
        add(lines, "save.manifest_hash", save.manifestHash());
        add(lines, "save.manifest_files", save.manifestFiles());
        add(lines, "save.manifest_bytes", save.manifestBytes());
        add(lines, "save.manifest_cached", save.manifestTaken() ? 1 : 0);
        for (SaveIdentityObserver.World world : worlds) {
            String prefix = "save.world." + safe(world.id()) + ".";
            add(lines, prefix + "id", safe(world.id()));
            add(lines, prefix + "region_id", safe(world.regionId()));
            add(lines, prefix + "players", world.players());
            add(lines, prefix + "loaded_chunks", world.loadedChunks());
        }
        fallback(lines, module);
    }

    /** The counters that mean work did not reach its destination: an unbound, dropped or abandoned
     * payload, a lost passthrough slot, a refused plan and a dropped commit. The total is a sum of
     * those counters and not a verdict on any of them. */
    private static void fallback(List<String> lines, KernelModule module) {
        long unbound = module.guard().payloads().unboundCount();
        long dropped = module.guard().payloads().droppedCount();
        long abandoned = module.guard().payloads().abandonedCount();
        long lost = module.passthrough().reading().lost();
        long planFailures = module.plans().failures();
        long commitDropped = module.commits().dropped();
        add(lines, "fallback.observation_only", 1);
        add(lines, "fallback.payload_unbound", unbound);
        add(lines, "fallback.payload_dropped", dropped);
        add(lines, "fallback.payload_abandoned", abandoned);
        add(lines, "fallback.passthrough_lost", lost);
        add(lines, "fallback.plan_failures", planFailures);
        add(lines, "fallback.commit_dropped", commitDropped);
        add(lines, "fallback.total", unbound + dropped + abandoned + lost + planFailures
            + commitDropped);
    }

    /** The per-instance attribution of the two tick faces: what the time inside a block entity tick
     * and inside a non-passenger entity row went to, by type, by mod and by colony, with the identity
     * of the costliest instance of each type and the record of every busy stall that was closed. Every
     * field is an observation request; no gate, verdict or release reads one. */
    private static void stallAttribution(List<String> lines, KernelModule module) {
        StallAttributionObserver attribution = module.attribution();
        add(lines, "attrib.observation_only", 1);
        add(lines, "attrib.installed", attribution.installed() ? 1 : 0);
        add(lines, "attrib.mod_source", attribution.modSource());
        add(lines, "attrib.colony_api", attribution.colonyAvailable() ? 1 : 0);
        add(lines, "attrib.cpu_supported", module.loadThread().cpuSupported() ? 1 : 0);
        add(lines, "attrib.long_tick_ms", ms(StallAttributionObserver.LONG_TICK_NANOS));
        add(lines, "attrib.live_dump_after_ms", ms(StallAttributionObserver.LIVE_DUMP_AFTER_NANOS));
        add(lines, "attrib.live_dumps", attribution.liveDumps());
        add(lines, "attrib.instances_capped", attribution.instancesCapped() ? 1 : 0);
        add(lines, "attrib.top_types", StallAttributionObserver.TYPE_TOP);
        add(lines, "attrib.unit", "milliseconds-wall-clock;cpu-is-thread-cpu-time");
        long tickBody = module.loadThread().tickBodyNanos();
        add(lines, "be.segment_per_body", format(tickBody <= 0L ? 0.0
            : attribution.segmentWall(true) / (double) tickBody));
        beFace(lines, attribution);
        entityFace(lines, attribution);
        add(lines, "stall.busy.episodes", attribution.episodeCount());
        StallAttributionObserver.Episode open = attribution.openEpisode();
        add(lines, "stall.busy.open", open == null ? 0 : 1);
        if (open != null) {
            episodeKeys(lines, "stall.busy.open.", open);
        }
        List<StallAttributionObserver.Episode> episodes = attribution.episodes();
        for (int index = 0; index < episodes.size(); index++) {
            episodeKeys(lines, "stall.busy.episode." + index + ".", episodes.get(index));
        }
        List<StallAttributionObserver.LongTick> longTicks = attribution.longTicks();
        add(lines, "tick.long_kept", longTicks.size());
        for (int index = 0; index < longTicks.size(); index++) {
            StallAttributionObserver.LongTick tick = longTicks.get(index);
            String prefix = "tick.long." + index + ".";
            add(lines, prefix + "face", tick.blockEntity() ? "block-entity" : "entity");
            add(lines, prefix + "ms", ms(tick.wallNanos()));
            add(lines, prefix + "cpu_ms", ms(Math.max(0L, tick.cpuNanos())));
            add(lines, prefix + "type", safe(tick.type()));
            add(lines, prefix + "mod", safe(tick.mod()));
            add(lines, prefix + "colony", safe(tick.colony()));
            add(lines, prefix + "label", safe(tick.label()));
            add(lines, prefix + "top", safe(tick.top()));
        }
    }

    private static void episodeKeys(List<String> lines, String prefix,
                                    StallAttributionObserver.Episode episode) {
        add(lines, prefix + "tick", episode.tick());
        add(lines, prefix + "wall_ms", ms(episode.wallNanos()));
        add(lines, prefix + "cpu_ms", ms(episode.cpuNanos()));
        add(lines, prefix + "park_ms", ms(episode.parkNanos()));
        add(lines, prefix + "offcpu_ms", ms(Math.max(0L,
            episode.wallNanos() - episode.cpuNanos() - episode.parkNanos())));
        add(lines, prefix + "blockentity_ms", ms(episode.blockEntityNanos()));
        add(lines, prefix + "blockentity_cpu_ms", ms(episode.blockEntityCpuNanos()));
        add(lines, prefix + "entity_ms", ms(episode.entityNanos()));
        add(lines, prefix + "entity_cpu_ms", ms(episode.entityCpuNanos()));
        add(lines, prefix + "gc_collections", episode.gcCollections());
        add(lines, prefix + "gc_ms", episode.gcMillis());
        add(lines, prefix + "live_dumps", episode.liveDumps());
        add(lines, prefix + "top_types", safe(joinTop(episode.topTypes())));
        add(lines, prefix + "top_mods", safe(joinTop(episode.topMods())));
        add(lines, prefix + "top_colonies", safe(joinTop(episode.topColonies())));
    }

    private static void beFace(List<String> lines, StallAttributionObserver attribution) {
        add(lines, "be.segment_ms", ms(attribution.segmentWall(true)));
        add(lines, "be.total_ms", ms(attribution.totalWall(true)));
        add(lines, "be.total_cpu_ms", ms(attribution.totalCpu(true)));
        add(lines, "be.ticks", attribution.totalTicks(true));
        add(lines, "be.types", attribution.typeCount(true));
        add(lines, "be.instances", attribution.totalInstances(true));
        add(lines, "be.unattributed_ms", ms(Math.max(0L,
            attribution.segmentWall(true) - attribution.totalWall(true))));
        add(lines, "be.offcpu_ms", ms(Math.max(0L,
            attribution.totalWall(true) - attribution.totalCpu(true))));
        for (StallAttributionObserver.Row row : attribution.types(true,
            StallAttributionObserver.TYPE_TOP)) {
            String prefix = "be.type." + safe(row.key()) + ".";
            add(lines, prefix + "ms", ms(row.wallNanos()));
            add(lines, prefix + "cpu_ms", ms(row.cpuNanos()));
            add(lines, prefix + "ticks", row.ticks());
            add(lines, prefix + "instances", row.instances());
            add(lines, prefix + "max_instance_ms", ms(row.maxInstanceNanos()));
            add(lines, prefix + "worst", safe(row.worst()));
        }
        for (StallAttributionObserver.Row row : attribution.mods(true)) {
            String prefix = "be.mod." + safe(row.key()) + ".";
            add(lines, prefix + "ms", ms(row.wallNanos()));
            add(lines, prefix + "cpu_ms", ms(row.cpuNanos()));
            add(lines, prefix + "ticks", row.ticks());
        }
        for (StallAttributionObserver.Row row : attribution.colonies(true)) {
            String prefix = "be.colony." + safe(row.key()) + ".";
            add(lines, prefix + "ms", ms(row.wallNanos()));
            add(lines, prefix + "ticks", row.ticks());
        }
        rowFace(lines, attribution);
    }

    /** The block entity rows classified against the batch predicate: how many of the rows one tick
     *  saw are inside a legal batch boundary, and which class keeps the others out. The four classes
     *  and the pending rows add up to the rows the face counted, so the widened share of the block
     *  entity rows is a reading a batch could carry and not a claim that any row was carried. */
    private static void rowFace(List<String> lines, StallAttributionObserver attribution) {
        add(lines, "be.row.observation_only", 1);
        add(lines, "be.row.batch_floor", StallAttributionObserver.ROW_BATCH_FLOOR);
        add(lines, "be.row.widened_total", attribution.rowWidened());
        add(lines, "be.row.singleton_total", attribution.rowSingleton());
        add(lines, "be.row.structure_third_total", attribution.rowStructureThird());
        add(lines, "be.row.no_region_total", attribution.rowRegionless());
        add(lines, "be.row.host_total", attribution.rowHost());
        add(lines, "be.row.buckets", attribution.rowBuckets());
        add(lines, "be.row.batch_buckets", attribution.rowBatchBuckets());
        add(lines, "be.row.ticks", attribution.rowTicks());
        add(lines, "be.row.pending", attribution.rowPending());
        add(lines, "be.row.structure_third_types",
            join(StallAttributionObserver.STRUCTURE_THIRD_TYPES));
    }

    private static void entityFace(List<String> lines, StallAttributionObserver attribution) {
        add(lines, "ent.total_ms", ms(attribution.totalWall(false)));
        add(lines, "ent.total_cpu_ms", ms(attribution.totalCpu(false)));
        add(lines, "ent.ticks", attribution.totalTicks(false));
        add(lines, "ent.classes", attribution.typeCount(false));
        add(lines, "ent.instances", attribution.totalInstances(false));
        add(lines, "ent.offcpu_ms", ms(Math.max(0L,
            attribution.totalWall(false) - attribution.totalCpu(false))));
        for (StallAttributionObserver.Row row : attribution.types(false,
            StallAttributionObserver.TYPE_TOP)) {
            String prefix = "ent.type." + safe(row.key()) + ".";
            add(lines, prefix + "ms", ms(row.wallNanos()));
            add(lines, prefix + "cpu_ms", ms(row.cpuNanos()));
            add(lines, prefix + "ticks", row.ticks());
            add(lines, prefix + "instances", row.instances());
            add(lines, prefix + "max_instance_ms", ms(row.maxInstanceNanos()));
            add(lines, prefix + "worst", safe(row.worst()));
        }
        for (StallAttributionObserver.Row row : attribution.mods(false)) {
            String prefix = "ent.mod." + safe(row.key()) + ".";
            add(lines, prefix + "ms", ms(row.wallNanos()));
            add(lines, prefix + "cpu_ms", ms(row.cpuNanos()));
            add(lines, prefix + "ticks", row.ticks());
        }
        for (StallAttributionObserver.Row row : attribution.colonies(false)) {
            String prefix = "ent.colony." + safe(row.key()) + ".";
            add(lines, prefix + "ms", ms(row.wallNanos()));
            add(lines, prefix + "ticks", row.ticks());
        }
    }

    /** The demand side of the chunk cache: how many asks it was given, how many were answered with a
     * chunk at the wanted status, how many futures are outstanding, and the long asks with the stack
     * they were taken at. The outstanding futures are the queue depth of this face. Observation only. */
    private static void chunkDemand(List<String> lines, KernelModule module) {
        ChunkDemandObserver demand = module.chunkDemand();
        long observedNanos = demand.observedNanos();
        double seconds = observedNanos / 1_000_000_000.0;
        add(lines, "demand.observation_only", 1);
        add(lines, "demand.installed", demand.installed() ? 1 : 0);
        add(lines, "demand.install_thread", safe(demand.mainThreadName()));
        add(lines, "demand.observed_ms", ms(observedNanos));
        add(lines, "demand.requests", demand.requests());
        add(lines, "demand.satisfied", demand.satisfied());
        add(lines, "demand.missed", demand.missed());
        add(lines, "demand.requests_per_s", format(seconds <= 0.0 ? 0.0
            : demand.requests() / seconds));
        add(lines, "demand.satisfied_per_s", format(seconds <= 0.0 ? 0.0
            : demand.satisfied() / seconds));
        add(lines, "demand.blocking_requests", demand.blockingRequests());
        add(lines, "demand.blocking_ms", ms(demand.blockingNanos()));
        add(lines, "demand.blocking_max_ms", ms(demand.blockingMaxNanos()));
        add(lines, "demand.long_bound_ms", ms(ChunkDemandObserver.LONG_DEMAND_NANOS));
        add(lines, "demand.futures_taken", demand.futuresTaken());
        add(lines, "demand.futures_completed", demand.futuresCompleted());
        add(lines, "demand.futures_satisfied", demand.futuresSatisfied());
        add(lines, "demand.futures_failed", demand.futuresFailed());
        add(lines, "demand.futures_in_flight", demand.futuresInFlight());
        add(lines, "demand.futures_peak", demand.futuresPeak());
        add(lines, "demand.futures_gap", demand.futuresTaken() - demand.futuresCompleted());
        add(lines, "demand.futures_ms", ms(demand.futuresNanos()));
        add(lines, "demand.futures_max_ms", ms(demand.futuresMaxNanos()));
        for (ChunkDemandObserver.WorldRow row : demand.worldRows()) {
            String prefix = "demand.world." + safe(row.key()) + ".";
            add(lines, prefix + "requests", row.requests());
            add(lines, prefix + "satisfied", row.satisfied());
            add(lines, prefix + "missed", row.missed());
            add(lines, prefix + "blocking_requests", row.blockingRequests());
            add(lines, prefix + "blocking_ms", ms(row.blockingNanos()));
            add(lines, prefix + "futures", row.futures());
            add(lines, prefix + "futures_completed", row.futuresCompleted());
            add(lines, prefix + "futures_satisfied", row.futuresSatisfied());
        }
        for (ChunkDemandObserver.StatusRow row : demand.statusRows()) {
            String prefix = "demand.status." + safe(row.key()) + ".";
            add(lines, prefix + "requests", row.requests());
            add(lines, prefix + "satisfied", row.satisfied());
        }
        List<ChunkDemandObserver.LongCall> calls = demand.longCalls();
        add(lines, "demand.long_kept", calls.size());
        for (int index = 0; index < calls.size(); index++) {
            ChunkDemandObserver.LongCall call = calls.get(index);
            String prefix = "demand.long." + index + ".";
            add(lines, prefix + "ms", ms(call.wallNanos()));
            add(lines, prefix + "cpu_ms", ms(Math.max(0L, call.cpuNanos())));
            add(lines, prefix + "world", safe(call.worldId()));
            add(lines, prefix + "status", safe(call.status()));
            add(lines, prefix + "blocking", call.blocking() ? 1 : 0);
            add(lines, prefix + "thread", safe(call.thread()));
            add(lines, prefix + "top", safe(call.top()));
        }
    }

    private static String joinTop(List<StallAttributionObserver.Row> rows) {
        if (rows.isEmpty()) {
            return "-";
        }
        StringBuilder builder = new StringBuilder();
        for (StallAttributionObserver.Row row : rows) {
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(row.key()).append(':').append(row.wallNanos() / 1_000_000L);
        }
        return builder.toString();
    }

    static String ms(long nanos) {
        return format(nanos / 1_000_000.0);
    }

    static String safe(String value) {
        if (value == null) {
            return "-";
        }
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
