/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity;

import java.util.Objects;

/**
 * The write-back identity gate: before any row is written, the live object is asked who it is and
 * which state revision it carries, and the row is refused when the answer no longer matches what the
 * freeze captured. The revision is read here, at the write itself, never only when the row was
 * frozen: a reservation that judged an older state cannot turn into a write of a newer one.
 */
public final class EntityWriteBackGate {

    /** What one gate check decided; only {@link #APPLIED} may be written. */
    public enum Verdict {
        APPLIED,
        REFUSED_NO_ENTITY,
        REFUSED_IDENTITY,
        REFUSED_GENERATION,
        REFUSED_REVISION
    }

    /** What the live world answers for one numeric id: the object it holds and its revision. */
    public record LiveRow(long uuidHigh, long uuidLow, long objectGeneration, long stateRevision) {
    }

    /** The live world read at the write point; a source that cannot answer returns null. */
    @FunctionalInterface
    public interface LiveSource {

        LiveRow read(String worldId, int entityId);
    }

    private EntityWriteBackGate() {
    }

    /** Reads the live row and decides whether the frozen identity may still write it. */
    public static Verdict check(EntityWriteBackIdentity expected, LiveSource live) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(live, "live");
        LiveRow row = live.read(expected.identity().worldId(), expected.identity().entityId());
        return verdict(expected, row);
    }

    /** Decides one row against the live answer; the revision is the one read at the write point. */
    public static Verdict verdict(EntityWriteBackIdentity expected, LiveRow row) {
        if (row == null) {
            return Verdict.REFUSED_NO_ENTITY;
        }
        EntityRowIdentity identity = expected.identity();
        if (row.uuidHigh() != identity.uuidHigh() || row.uuidLow() != identity.uuidLow()) {
            return Verdict.REFUSED_IDENTITY;
        }
        if (expected.generationDeclared()
            && row.objectGeneration() != expected.objectGeneration()) {
            return Verdict.REFUSED_GENERATION;
        }
        if (expected.revisionDeclared()
            && row.stateRevision() != expected.expectedStateRevision()) {
            return Verdict.REFUSED_REVISION;
        }
        return Verdict.APPLIED;
    }
}
