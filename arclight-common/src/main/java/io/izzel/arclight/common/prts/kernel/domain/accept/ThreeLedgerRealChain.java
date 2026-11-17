/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.accept;

import io.izzel.arclight.common.prts.kernel.arena.ArenaLedger;
import io.izzel.arclight.common.prts.kernel.auth.OwnerRegistry;
import io.izzel.arclight.common.prts.kernel.generic.ExecutionCohort;
import io.izzel.arclight.common.prts.kernel.intent.IntentQueue;
import io.izzel.arclight.common.prts.kernel.jobs.WorkAccount;

import java.util.ArrayList;
import java.util.List;

/** The minimal real three-ledger chain: for every unit of one real work account it joins the
 business account's unit view, the execution cohort's witness and the live resource counts under
 the same unit identity. A source that is not supplied is reported as unbound with its name and
 sentinel values, never filled with a zero or a constant epoch. The join is read-only. */
public final class ThreeLedgerRealChain {

    /** The live resource counts bound to one world; unbound fields stay -1. */
    public record Resource(boolean bound, long ownerTokens, int arenaPinned, int intents,
                           long arenaClaims, long arenaReleases, long oldestObligationAge) {

        public static final Resource UNBOUND = new Resource(false, -1L, -1, -1, -1L, -1L, -1L);
    }

    /** One joined unit: its identity plus which ledgers were readable. */
    public record Row(String unitId, String key, long ordinal, WorkAccount.Outcome outcome,
                      boolean effectBound, boolean effectTerminal, List<String> violations) {
    }

    /** The whole join with explicit unbound sources and violations. */
    public record Report(List<Row> rows, long joined, List<String> unboundSources,
                         List<String> violations, Resource resource) {
    }

    private ThreeLedgerRealChain() {
    }

    /** Joins one real account against the cohort and the resource ledgers; missing sources are NA. */
    public static Report join(WorkAccount account, ExecutionCohort cohort, OwnerRegistry owners,
                              ArenaLedger arena, IntentQueue intents, String worldId,
                              long oldestObligationAge) {
        List<String> unbound = new ArrayList<>();
        if (account == null) {
            unbound.add("business-account");
        }
        if (cohort == null) {
            unbound.add("execution-cohort");
        }
        if (owners == null || arena == null || intents == null) {
            unbound.add("resource-ledger");
        }
        Resource resource = owners == null || arena == null || intents == null
            ? Resource.UNBOUND
            : new Resource(true, owners.activeTokens(), arena.pinnedCount(),
                intents.depth(worldId), arena.claims(), arena.releases(), oldestObligationAge);
        if (account == null) {
            return new Report(List.of(), 0L, List.copyOf(unbound), List.of(), resource);
        }
        List<Row> rows = new ArrayList<>();
        List<String> violations = new ArrayList<>();
        long joined = 0L;
        for (WorkAccount.UnitView unit : account.snapshot().units()) {
            ExecutionCohort.Witness witness = cohort == null ? null : cohort.witness(unit.key());
            boolean effectBound = witness != null;
            boolean terminal = effectBound && witness.terminal();
            long ordinal = unit.unitId().contains("#")
                ? parseOrdinal(unit.unitId().substring(unit.unitId().indexOf('#') + 1)) : 0L;
            List<String> rowViolations = new ArrayList<>();
            if (unit.outcome() == WorkAccount.Outcome.DONE && (!effectBound || !terminal)) {
                rowViolations.add("done-without-effect");
            }
            if (unit.outcome() != null && resource.bound() && resource.ownerTokens() > 0L) {
                rowViolations.add("terminal-holds-resource");
            }
            if ((unit.outcome() == WorkAccount.Outcome.FAULT
                || unit.outcome() == WorkAccount.Outcome.PARTIAL_FAULT)
                && resource.bound() && resource.arenaPinned() == 0
                && resource.intents() == 0 && resource.oldestObligationAge() <= 0L) {
                rowViolations.add("fault-without-obligation");
            }
            violations.addAll(rowViolations);
            if (effectBound && resource.bound()) {
                joined++;
            }
            rows.add(new Row(unit.unitId(), unit.key(), ordinal, unit.outcome(), effectBound,
                terminal, List.copyOf(rowViolations)));
        }
        return new Report(List.copyOf(rows), joined, List.copyOf(unbound),
            List.copyOf(violations), resource);
    }

    private static long parseOrdinal(String text) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException unavailable) {
            return -1L;
        }
    }
}
