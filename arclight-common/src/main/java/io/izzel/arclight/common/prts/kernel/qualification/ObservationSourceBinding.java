/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.qualification;

import io.izzel.arclight.common.prts.kernel.arena.ArenaLedger;
import io.izzel.arclight.common.prts.kernel.auth.OwnerRegistry;
import io.izzel.arclight.common.prts.kernel.commit.CommitReservation;
import io.izzel.arclight.common.prts.kernel.domain.entity.EntityDueWindow;
import io.izzel.arclight.common.prts.kernel.intent.IntentQueue;
import io.izzel.arclight.common.prts.kernel.jobs.WorkAccount;

import java.util.ArrayList;
import java.util.List;

/** The read-only observation-source binding: every field is either derived from a real source or
 * explicitly unbound with its reason. The caller cannot supply a boolean or a classification string
 * to stand in for a source, and a missing source never becomes a constant epoch or an empty hash. */
public final class ObservationSourceBinding {

    /** One observation field: bound name/value or the reason it is not bound. */
    public record Source(String field, boolean bound, String value, String reason) {
    }

    /** The whole binding report. */
    public record Report(List<Source> sources) {

        public Source source(String field) {
            for (Source source : sources) {
                if (source.field().equals(field)) {
                    return source;
                }
            }
            return null;
        }

        public boolean allBound() {
            return sources.stream().allMatch(Source::bound);
        }
    }

    private ObservationSourceBinding() {
    }

    /** Derives every field from real state; the four former caller self-claims are gone. */
    public static Report bind(WorkAccount account, OwnerRegistry owners, ArenaLedger arena,
                              IntentQueue intents, EntityDueWindow.Row row,
                              CommitReservation reservations, long worldIncarnation,
                              long logicalOrdinal) {
        List<Source> sources = new ArrayList<>();
        boolean identityBound = account != null && !account.snapshot().units().isEmpty()
            && worldIncarnation >= 0L && logicalOrdinal >= 0L;
        sources.add(new Source("row-identity", identityBound,
            identityBound ? account.snapshot().units().get(0).unitId() : "-",
            identityBound ? "" : "unbound:row-identity-source"));
        boolean noAiBound = row != null && row.classTag() != null && !row.classTag().isBlank();
        sources.add(new Source("noai-classification", noAiBound,
            noAiBound ? row.reason().name() + ":" + row.classTag() : "-",
            noAiBound ? "" : "unbound:no-real-eligibility-row"));
        boolean resourceBound = owners != null && arena != null && intents != null;
        sources.add(new Source("owner-arena-account", resourceBound,
            resourceBound ? owners.activeTokens() + "/" + arena.pinnedCount() + "/"
                + intents.depth() : "-",
            resourceBound ? "" : "unbound:owner-arena-account"));
        boolean effectBound = reservations != null;
        sources.add(new Source("real-effect", effectBound,
            effectBound ? reservations.terminalLedger().size() + "/"
                + reservations.faultLedger().size() : "-",
            effectBound ? "" : "unbound:no-commit-reservation"));
        boolean publishBound = reservations != null && !reservations.terminalLedger().isEmpty();
        long published = reservations == null ? 0L
            : reservations.terminalLedger().stream().filter(entry -> entry.successBatch() > 0)
                .count();
        sources.add(new Source("publish-receipt", publishBound,
            publishBound ? published + "/" + reservations.terminalLedger().size() : "-",
            publishBound ? "" : "unbound:no-terminal-publish-entry"));
        boolean rejectionBound = account != null;
        sources.add(new Source("unready-rejection", rejectionBound,
            rejectionBound ? String.valueOf(account.snapshot().rejectedBeforeEffect()) : "-",
            rejectionBound ? "" : "unbound:no-account"));
        boolean debtBound = reservations != null;
        sources.add(new Source("oldest-debt-age", debtBound,
            debtBound ? String.valueOf(reservations.oldestPendingAge()) : "-",
            debtBound ? "" : "unbound:no-commit-reservation"));
        return new Report(List.copyOf(sources));
    }
}
