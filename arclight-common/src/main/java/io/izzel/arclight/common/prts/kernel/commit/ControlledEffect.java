/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.commit;

/** One controlled effect over a real cell: the action runs once, writes the cell, and reports the
 * terminal a publisher adapter hands to the commit controller. A second call reports the first
 * outcome without running the action again. The three terminal payloads share one effect reference,
 * so the request, the store record and the typed line all name the same effect. */
public final class ControlledEffect {

    /** The one native write an effect may perform. It returns whether the write was published. */
    @FunctionalInterface
    public interface Action {

        /** Writes the cell and reports whether the write was published. */
        boolean apply(Cell cell);
    }

    /** A mutable block-state cell: the state object an effect writes and a reader observes. The
     * revision and the write count move only through {@link #write(String)}. */
    public static final class Cell {

        private final String key;
        private String state;
        private long revision;
        private int writes;
        private boolean dirty;

        /** Creates a cell with an initial state. */
        public Cell(String key, String state) {
            if (key == null || key.isEmpty() || state == null || state.isEmpty()) {
                throw new IllegalArgumentException("a cell needs a key and a state");
            }
            this.key = key;
            this.state = state;
        }

        /** The cell this state belongs to. */
        public synchronized String key() {
            return key;
        }

        /** The current block state. */
        public synchronized String state() {
            return state;
        }

        /** How many times the state was written. */
        public synchronized long revision() {
            return revision;
        }

        /** The number of writes this cell took. */
        public synchronized int writes() {
            return writes;
        }

        /** Whether the state changed since the cell was made. */
        public synchronized boolean dirty() {
            return dirty;
        }

        /** Replaces the state and moves the revision and the write count by one. */
        public synchronized void write(String nextState) {
            if (nextState == null || nextState.isEmpty()) {
                throw new IllegalArgumentException("a cell state cannot be empty");
            }
            state = nextState;
            revision++;
            writes++;
            dirty = true;
        }
    }

    private final Cell cell;
    private final Action action;
    private final EffectStore.Effect published;
    private final EffectStore.Effect partial;
    private final EffectStore.Effect failed;
    private boolean applied;
    private CommitTicket.Outcome outcome;

    /** Binds one action to one cell and the three terminals the request may report. */
    public ControlledEffect(Cell cell, Action action, EffectStore.Effect published,
                            EffectStore.Effect partial, EffectStore.Effect failed) {
        if (cell == null || action == null || published == null || partial == null || failed == null) {
            throw new IllegalArgumentException("an effect needs a cell, an action and three terminals");
        }
        long effectRef = published.effectRef();
        if (effectRef <= 0L || partial.effectRef() != effectRef || failed.effectRef() != effectRef) {
            throw new IllegalArgumentException("the three terminals need one positive reference");
        }
        if (EffectStore.normalizedRows(partial.rows()).size()
                != EffectStore.normalizedRows(published.rows()).size()
            || EffectStore.normalizedRows(failed.rows()).size()
                != EffectStore.normalizedRows(published.rows()).size()) {
            throw new IllegalArgumentException("the three terminals need one row count");
        }
        this.cell = cell;
        this.action = action;
        this.published = published;
        this.partial = partial;
        this.failed = failed;
    }

    /** Runs the action once and reports the terminal it reached. A second call returns the first
     * outcome and changes nothing; an action that throws is reported as the partial terminal. */
    public synchronized CommitTicket.Outcome apply() {
        if (applied) {
            if (outcome == null) {
                throw new IllegalStateException("the effect action re-entered apply");
            }
            return outcome;
        }
        applied = true;
        try {
            outcome = action.apply(cell) ? outcome(CommitTicket.Terminal.PUBLISHED, published, "")
                : outcome(CommitTicket.Terminal.FAILED, failed, "");
        } catch (RuntimeException thrown) {
            outcome = outcome(CommitTicket.Terminal.PARTIAL, partial, "publisher-threw");
        }
        return outcome;
    }

    /** The publish action this binding hands the controller: it calls {@link #apply()} once. */
    public CommitController.Publisher publisher() {
        return ticket -> apply();
    }

    private static CommitTicket.Outcome outcome(CommitTicket.Terminal terminal,
                                                EffectStore.Effect effect, String note) {
        int units = terminal == CommitTicket.Terminal.PUBLISHED ? 1 : 0;
        return new CommitTicket.Outcome(terminal, effect.operationKind(),
            EffectStore.normalizedRows(effect.rows()), units, note);
    }

    /** Whether the action was entered. */
    public synchronized boolean applied() {
        return applied;
    }

    /** How often the action was entered: one or zero. */
    public synchronized int applyCount() {
        return applied ? 1 : 0;
    }

    /** The outcome of the first call, or null before it. */
    public synchronized CommitTicket.Outcome outcome() {
        return outcome;
    }

    /** The state object this effect writes. */
    public Cell cell() {
        return cell;
    }

    /** The terminal recorded when the action publishes. */
    public EffectStore.Effect published() {
        return published;
    }

    /** The terminal recorded when the action throws. */
    public EffectStore.Effect partial() {
        return partial;
    }

    /** The terminal recorded when the action reports no publish. */
    public EffectStore.Effect failed() {
        return failed;
    }
}
