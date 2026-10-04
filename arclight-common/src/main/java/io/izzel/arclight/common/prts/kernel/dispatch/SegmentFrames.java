/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * The frame of one segment: one world slice of one tick, the rows the plan point claimed for
 * takeover and the rows it only observes, the outcome every ownership row ended in, and the checks
 * the frame passed. The two row sets are booked apart - an observed row has no token, no answer and
 * no output row, so it can neither be committed nor counted as an ownership row.
 *
 * <p>The ledger sums the frames a reader booked. Nothing in it decides anything: no value here can
 * skip a row or land one, and every counter reads zero while no frame was booked, so the leg that
 * leaves the path off still reports the same line at zero instead of an absence.
 */
package io.izzel.arclight.common.prts.kernel.dispatch;

/** One segment frame and the ledger that sums the frames a reader booked. */
public final class SegmentFrames {

    /** A closed segment frame: the two row sets of the slice apart, the outcome of every ownership
     * row, and the verdicts of the three layer checks and of the row account. */
    public record Frame(long tickIndex, String worldId, long segmentEpoch, int ownedRows,
                        int observedRows, int committed, int fellBack, int notEntered,
                        int observedEntries, int setConflicts, int unbookedCommits,
                        int ordinalBroken, int entityRejected, int worldRejected,
                        int segmentRejected, boolean ledgerOk) {

        /** The rows this frame carries in both sets. */
        public int rows() {
            return ownedRows + observedRows;
        }
    }

    /** The frames a reader booked, summed into the counters the readout publishes. */
    public static final class Ledger {

        private long frames;
        private long ownedRows;
        private long observedRows;
        private long committed;
        private long fellBack;
        private long notEntered;
        private long observedEntries;
        private long setConflicts;
        private long unbookedCommits;
        private long ordinalBroken;
        private long brokenFrames;
        private long brokenLedgers;
        private long lastTick = -1L;

        /** Books one closed frame: both row sets, every outcome and every check verdict. */
        public void note(Frame frame) {
            frames++;
            ownedRows += frame.ownedRows();
            observedRows += frame.observedRows();
            committed += frame.committed();
            fellBack += frame.fellBack();
            notEntered += frame.notEntered();
            observedEntries += frame.observedEntries();
            setConflicts += frame.setConflicts();
            unbookedCommits += frame.unbookedCommits();
            ordinalBroken += frame.ordinalBroken();
            if (frame.setConflicts() > 0) {
                brokenFrames++;
            }
            if (!frame.ledgerOk()) {
                brokenLedgers++;
            }
            lastTick = frame.tickIndex();
        }

        public long frames() {
            return frames;
        }

        /** The rows the booked frames carry in both sets. */
        public long rows() {
            return ownedRows + observedRows;
        }

        public long ownedRows() {
            return ownedRows;
        }

        public long observedRows() {
            return observedRows;
        }

        public long committed() {
            return committed;
        }

        public long fellBack() {
            return fellBack;
        }

        public long notEntered() {
            return notEntered;
        }

        public long observedEntries() {
            return observedEntries;
        }

        public long setConflicts() {
            return setConflicts;
        }

        public long unbookedCommits() {
            return unbookedCommits;
        }

        public long ordinalBroken() {
            return ordinalBroken;
        }

        public long brokenFrames() {
            return brokenFrames;
        }

        public long brokenLedgers() {
            return brokenLedgers;
        }

        public long lastTick() {
            return lastTick;
        }

        /** Whether every ownership row of every booked frame ended in exactly one outcome. */
        public boolean accounted() {
            return ownedRows == committed + fellBack + notEntered;
        }

        /** Whether the two row sets of every booked frame stayed apart. */
        public boolean setsApart() {
            return setConflicts == 0L;
        }

        /** One line for the evidence cadence: both row sets, every outcome and the check verdicts. */
        public String evidenceLine() {
            return "[PRTS] segment-frame: frames=" + frames
                + " rows=" + rows()
                + " owned_rows=" + ownedRows
                + " observed_rows=" + observedRows
                + " observed_entries=" + observedEntries
                + " committed=" + committed
                + " fallback=" + fellBack
                + " not_entered=" + notEntered
                + " set_conflicts=" + setConflicts
                + " unbooked_commits=" + unbookedCommits
                + " ordinal_violations=" + ordinalBroken
                + " broken_frames=" + brokenFrames
                + " broken_ledgers=" + brokenLedgers
                + " last_tick=" + lastTick
                + " sets_apart=" + (setsApart() ? "ok" : "broken")
                + " ledger=" + (accounted() ? "ok" : "broken");
        }

        /** Clears every counter; the readout reset and the tests use it. */
        public void reset() {
            frames = 0L;
            ownedRows = 0L;
            observedRows = 0L;
            committed = 0L;
            fellBack = 0L;
            notEntered = 0L;
            observedEntries = 0L;
            setConflicts = 0L;
            unbookedCommits = 0L;
            ordinalBroken = 0L;
            brokenFrames = 0L;
            brokenLedgers = 0L;
            lastTick = -1L;
        }
    }

    private SegmentFrames() {
    }
}
