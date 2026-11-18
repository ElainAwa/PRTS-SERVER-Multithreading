/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.interaction;

import io.izzel.arclight.common.prts.kernel.commit.EffectStore;
import io.izzel.arclight.common.prts.kernel.domain.entity.owner.EntityRowOwnershipWindow;
import io.izzel.arclight.common.prts.kernel.intent.CommitOrder;
import io.izzel.arclight.common.prts.kernel.intent.IntentQueue;
import io.izzel.arclight.common.prts.kernel.intent.WriteIntent;

import java.util.List;
import java.util.Objects;

/** The default-off REST write split: the owner thread only submits (enqueues an intent, claims or
 * releases the POI record and reserves the typed effect), while the host commits/applies outside the
 * owner window. No second queue, no parallel commit and no world write on the owner thread. */
public final class VillagerRestWriteBridge {

    /** The REST world-write points inside the named W1b scope. */
    public enum Kind {
        BED_OCCUPY,
        SLEEP_POSE,
        SCHEDULE_ACTIVITY,
        HOME_POI_TAKE,
        HOME_POI_RELEASE
    }

    /** One owner update: the typed identity plus the target point. */
    public record Update(String unitId, long ordinal, String worldId, long worldIncarnation,
                         long generation, long revision, Kind kind, long targetPos) {

        public Update {
            Objects.requireNonNull(unitId, "unitId");
            Objects.requireNonNull(worldId, "worldId");
            Objects.requireNonNull(kind, "kind");
            if (ordinal < 0L || worldIncarnation < 0L || generation < 0L || revision < 0L) {
                throw new IllegalArgumentException("a REST update needs non-negative witnesses");
            }
        }
    }

    /** The owner-side submission: no intent commit and no effect record happened yet. */
    public record Submission(boolean accepted, String reason, Update update, long frozenOrder,
                             EffectStore.Effect effect, EffectStore.Slot effectSlot,
                             PoiClaimStore.Claim poi) {
    }

    /** The host-side outcome after the bounded commit/apply. */
    public record Result(boolean applied, String reason, CommitOrder intent,
                         EffectStore.Recorded effect, PoiClaimStore.Claim poi) {
    }

    private VillagerRestWriteBridge() {
    }

    /** Whether the REST bridge is declared; off in every shipped build. */
    public static boolean enabled() {
        return EntityRowOwnershipWindow.enabled();
    }

    /** Owner-side submit only: enqueue/claim/reserve, never commit or apply. */
    public static Submission submit(IntentQueue intents, EffectStore effects, PoiClaimStore poi,
                                    Update update) {
        Objects.requireNonNull(intents, "intents");
        Objects.requireNonNull(effects, "effects");
        Objects.requireNonNull(poi, "poi");
        Objects.requireNonNull(update, "update");
        if (!enabled()) {
            return new Submission(false, "property-off", update, -1L, null, null, null);
        }
        EffectStore.Effect effect = effectOf(update);
        EffectStore.Result reserved = effects.reserve(effect);
        if (!reserved.admitted()) {
            return new Submission(false, "effect-refused", update, -1L, effect, null, null);
        }
        if (update.kind() == Kind.HOME_POI_TAKE
            || update.kind() == Kind.HOME_POI_RELEASE) {
            String record = recordOf(update);
            PoiClaimStore.Claim claim = update.kind() == Kind.HOME_POI_TAKE
                ? poi.claim(record, holder(update)) : poi.release(record, holder(update));
            if (!claim.granted()) {
                effects.release(reserved.slot());
                return new Submission(false, "poi-refused", update, -1L, effect, null, claim);
            }
            return new Submission(true, "", update, -1L, effect, reserved.slot(), claim);
        }
        IntentQueue.EnqueueResult enqueued = intents.enqueue(WriteIntent.draft(
            intents.nextIntentId(), update.worldId(), update.worldId(), "entity",
            update.revision(), update.worldIncarnation(), "rest:" + update.kind().name() + ":"
                + update.targetPos(), "entity-rest", holder(update)));
        if (!enqueued.accepted()) {
            effects.release(reserved.slot());
            return new Submission(false, enqueued.code().text(), update, -1L, effect, null, null);
        }
        return new Submission(true, "", update, enqueued.handle().frozenOrder(), effect,
            reserved.slot(), null);
    }

    /** Host-side commit/apply: the only place the intent payload and typed effect are committed. */
    public static Result commit(Submission submission, IntentQueue intents, EffectStore effects,
                                PoiClaimStore poi) {
        Objects.requireNonNull(poi, "poi");
        if (!submission.accepted()) {
            return new Result(false, submission.reason(), null, null, submission.poi());
        }
        CommitOrder order = submission.poi() == null
            ? intents.commit(submission.update().worldId(), submission.frozenOrder(),
                submission.update().revision()) : null;
        if (order != null && !order.committed()) {
            effects.release(submission.effectSlot());
            return new Result(false, order.code() == null ? "commit-refused" : order.code().text(),
                order, null, submission.poi());
        }
        EffectStore.Result recorded = effects.commit(submission.effectSlot(), submission.effect());
        boolean applied = recorded.admitted() && (submission.poi() == null
            || submission.poi().granted());
        String reason = applied ? "" : "effect-record-refused";
        if (!recorded.admitted() && submission.poi() != null && submission.poi().granted()) {
            if (!rollbackClaim(poi, submission.update())) {
                reason = "effect-record-refused:rollback-refused";
            }
        }
        return new Result(applied, reason, order, recorded.record(), submission.poi());
    }

    /** Aborts a submission after a window failure: release the reserved effect and a granted take
     * claim; the intent head stays with the existing queue semantics. */
    public static void abort(Submission submission, EffectStore effects, PoiClaimStore poi) {
        Objects.requireNonNull(effects, "effects");
        Objects.requireNonNull(poi, "poi");
        if (!submission.accepted()) {
            return;
        }
        if (submission.effectSlot() != null) {
            effects.release(submission.effectSlot());
        }
        if (submission.poi() != null && submission.poi().granted()) {
            // The host commit is the only writer at this point; a failed rollback can only mean an
            // exceptional holder race, and the existing POI contract exposes no further remedy.
            rollbackClaim(poi, submission.update());
        }
    }

    private static boolean rollbackClaim(PoiClaimStore poi, Update update) {
        if (update.kind() == Kind.HOME_POI_TAKE) {
            return poi.release(recordOf(update), holder(update)).granted();
        }
        if (update.kind() == Kind.HOME_POI_RELEASE) {
            // The owner released the holder; a refused effect commit is restored by claiming it
            // back under the same holder on the host commit thread.
            return poi.claim(recordOf(update), holder(update)).granted();
        }
        return false;
    }

    private static EffectStore.Effect effectOf(Update update) {
        return new EffectStore.Effect(update.ordinal() + 1L, update.kind().name(), "applied",
            List.of("row=" + update.unitId()));
    }

    private static String recordOf(Update update) {
        return "world=" + update.worldId() + "|incarnation=" + update.worldIncarnation()
            + "|poi=" + update.targetPos();
    }

    private static String holder(Update update) {
        return update.unitId();
    }
}
