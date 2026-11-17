/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.qualification;

import io.izzel.arclight.common.prts.kernel.arena.ArenaLedger;
import io.izzel.arclight.common.prts.kernel.auth.OwnerRegistry;
import io.izzel.arclight.common.prts.kernel.intent.IntentQueue;
import io.izzel.arclight.common.prts.kernel.jobs.WorkAccount;

import java.util.ArrayList;
import java.util.List;

/** The read-only observation-source binding: every field that a strict report needs is either bound
 * to a real source with its value, or explicitly unbound with its reason. There is no constant
 * epoch, empty hash or fixed controller fallback, and a missing source never becomes a zero. */
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

    /** Binds the real sources; an absent one is named unbound instead of holding a sentinel value. */
    public static Report bind(WorkAccount account, OwnerRegistry owners, ArenaLedger arena,
                              IntentQueue intents, long worldIncarnation, long logicalOrdinal,
                              String noAiClassification, boolean effectSource,
                              boolean publishReceipt, long oldestDebtAge) {
        List<Source> sources = new ArrayList<>();
        boolean identityBound = account != null && !account.snapshot().units().isEmpty()
            && worldIncarnation >= 0L && logicalOrdinal >= 0L;
        sources.add(new Source("row-identity", identityBound,
            identityBound ? account.snapshot().units().get(0).unitId() : "-",
            identityBound ? "" : "unbound:row-identity-source"));
        boolean noAiBound = noAiClassification != null && !noAiClassification.isBlank();
        sources.add(new Source("noai-classification", noAiBound,
            noAiBound ? noAiClassification : "-",
            noAiBound ? "" : "unbound:no-noai-classification"));
        boolean resourceBound = owners != null && arena != null && intents != null;
        sources.add(new Source("owner-arena-account", resourceBound,
            resourceBound ? owners.activeTokens() + "/" + arena.pinnedCount() + "/"
                + intents.depth() : "-",
            resourceBound ? "" : "unbound:owner-arena-account"));
        sources.add(new Source("real-effect", effectSource, effectSource ? "bound" : "-",
            effectSource ? "" : "unbound:no-real-effect-export"));
        sources.add(new Source("publish-receipt", publishReceipt, publishReceipt ? "bound" : "-",
            publishReceipt ? "" : "unbound:no-publish-receipt"));
        boolean rejectionBound = account != null;
        sources.add(new Source("unready-rejection", rejectionBound,
            rejectionBound ? String.valueOf(account.snapshot().rejectedBeforeEffect()) : "-",
            rejectionBound ? "" : "unbound:no-account"));
        boolean debtBound = oldestDebtAge >= 0L;
        sources.add(new Source("oldest-debt-age", debtBound,
            debtBound ? String.valueOf(oldestDebtAge) : "-",
            debtBound ? "" : "unbound:no-oldest-debt-age"));
        return new Report(List.copyOf(sources));
    }
}
