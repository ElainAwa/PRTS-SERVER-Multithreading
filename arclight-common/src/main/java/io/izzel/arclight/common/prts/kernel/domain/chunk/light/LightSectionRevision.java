/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.chunk.light;

/** The revision state of one light or heightmap section: a solve is pinned to the dirty revision and
 * boundary inflow it started from; a change during the solve derives a successor instead of
 * clearing the dirt, and only a solve at the current revision converges. */
public final class LightSectionRevision {

    /** The two consumers of one section revision; both keep the same dirt semantics. */
    public enum Kind {
        LIGHT,
        HEIGHTMAP
    }

    /** The pinned revisions one solve started from. */
    public record SolveToken(long dirtyRevision, long boundaryInflowVersion) {
    }

    private final Kind kind;
    private long dirtyRevision;
    private long boundaryInflowVersion;
    private long convergedRevision;
    private boolean solving;
    private boolean successorPending;
    private long staleSolves;

    public LightSectionRevision(Kind kind) {
        if (kind == null) {
            throw new IllegalArgumentException("a section revision needs its kind");
        }
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    /** Raises the dirty revision; a change during a solve derives a successor, never clears dirt. */
    public synchronized void markDirty(long revision) {
        if (revision > dirtyRevision) {
            dirtyRevision = revision;
        }
        if (solving) {
            successorPending = true;
        }
    }

    /** Notes a neighbour boundary inflow version; a change during a solve derives a successor. */
    public synchronized void noteBoundaryInflow(long version) {
        if (version != boundaryInflowVersion) {
            boundaryInflowVersion = version;
            if (solving) {
                successorPending = true;
            }
        }
    }

    /** Pins one solve to the current revisions; a second concurrent solve is refused. */
    public synchronized SolveToken beginSolve() {
        if (solving) {
            return null;
        }
        solving = true;
        successorPending = false;
        return new SolveToken(dirtyRevision, boundaryInflowVersion);
    }

    /** Converges only a solve at the current revisions; otherwise a successor is required. */
    public synchronized boolean converge(SolveToken token) {
        if (token == null || !solving) {
            staleSolves++;
            return false;
        }
        if (token.dirtyRevision() != dirtyRevision
            || token.boundaryInflowVersion() != boundaryInflowVersion) {
            successorPending = true;
            return false;
        }
        convergedRevision = dirtyRevision;
        solving = false;
        successorPending = false;
        return true;
    }

    /** Ends one non-converged solve so its successor may start; dirt and versions stay untouched. */
    public synchronized void endWithSuccessor() {
        solving = false;
    }

    public synchronized boolean successorPending() {
        return successorPending;
    }

    /** Half-lit: the settled revision is behind the dirt or a successor is still owed. */
    public synchronized boolean halfLit() {
        return convergedRevision < dirtyRevision || successorPending;
    }

    public synchronized boolean converged() {
        return !halfLit() && !solving && convergedRevision == dirtyRevision;
    }

    public synchronized long dirtyRevision() {
        return dirtyRevision;
    }

    public synchronized long boundaryInflowVersion() {
        return boundaryInflowVersion;
    }

    public synchronized long convergedRevision() {
        return convergedRevision;
    }

    public synchronized long staleSolves() {
        return staleSolves;
    }
}
