/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The settlement probe a stop must pass before it is called closed: the arena must be unpinned and
 * without active claims, the window account closed and balanced, every cohort unit terminal, no
 * intent or commit in flight, no owner still active, no version slot still held and no payload
 * still bound. The probe reads plain facts, so the same contract can be attached to a window and
 * checked by tests; it writes nothing and never waits.
 */
public final class SettlementVerifier {

    /** The plain numbers one stop reports; every field is read after the close, never during it. */
    public record Facts(long arenaPinned, long arenaActiveClaims, long workOffered,
                        long workSeated, long workPending, long workClosed, boolean workBalanced,
                        long cohortExpected, long cohortTerminal, long intentsInFlight,
                        long commitInFlight, long ownersActive, long versionsActiveSlots,
                        long payloadsBound) {

        public Facts {
            if (arenaPinned < 0L || arenaActiveClaims < 0L || workOffered < 0L || workSeated < 0L
                || workPending < 0L || workClosed < 0L || cohortExpected < 0L
                || cohortTerminal < 0L || intentsInFlight < 0L || commitInFlight < 0L
                || ownersActive < 0L || versionsActiveSlots < 0L || payloadsBound < 0L) {
                throw new IllegalArgumentException("a settlement fact cannot be negative");
            }
        }
    }

    /** What the probe found: whether the stop is settled and which obligations remain open. */
    public record Result(boolean settled, List<String> failures) {

        public Result {
            failures = List.copyOf(failures);
        }
    }

    /** A window that can answer the stop facts when asked. */
    @FunctionalInterface
    public interface Source {

        Facts read();
    }

    private SettlementVerifier() {
    }

    /** Reads the source and decides the stop. */
    public static Result check(Source source) {
        Objects.requireNonNull(source, "source");
        return check(source.read());
    }

    /** Decides one stop from its plain facts; every open obligation is named. */
    public static Result check(Facts facts) {
        Objects.requireNonNull(facts, "facts");
        List<String> failures = new ArrayList<>();
        if (facts.arenaPinned() != 0L) {
            failures.add("arena-pinned:" + facts.arenaPinned());
        }
        if (facts.arenaActiveClaims() != 0L) {
            failures.add("arena-claims:" + facts.arenaActiveClaims());
        }
        if (facts.workPending() != 0L) {
            failures.add("work-pending:" + facts.workPending());
        }
        if (!facts.workBalanced()) {
            failures.add("work-unbalanced");
        }
        if (facts.workClosed() != facts.workOffered()) {
            failures.add("work-not-closed:" + facts.workClosed() + "/" + facts.workOffered());
        }
        if (facts.cohortTerminal() != facts.cohortExpected()) {
            failures.add("cohort-not-terminal:" + facts.cohortTerminal() + "/"
                + facts.cohortExpected());
        }
        if (facts.intentsInFlight() != 0L) {
            failures.add("intents-in-flight:" + facts.intentsInFlight());
        }
        if (facts.commitInFlight() != 0L) {
            failures.add("commit-in-flight:" + facts.commitInFlight());
        }
        if (facts.ownersActive() != 0L) {
            failures.add("owners-active:" + facts.ownersActive());
        }
        if (facts.versionsActiveSlots() != 0L) {
            failures.add("versions-active:" + facts.versionsActiveSlots());
        }
        if (facts.payloadsBound() != 0L) {
            failures.add("payloads-bound:" + facts.payloadsBound());
        }
        return new Result(failures.isEmpty(), failures);
    }

    /** The facts a fully settled stop reports. */
    public static Facts settledFacts(long offered) {
        return new Facts(0L, 0L, offered, offered, 0L, offered, true, offered, offered, 0L, 0L,
            0L, 0L, 0L);
    }
}
