/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec;

import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** The staged search of the one row whose body runs on this thread; the first matching call takes it. */
public final class NativePathStage {

    /** The answer to one staged lookup: whether a staged search matched, the path it produced, and
     * whether it was an ordinary start prediction, which only the shadow mode compares. */
    public record Hit(boolean matched, Path path, boolean ordinary) {

        public static final Hit NONE = new Hit(false, null, false);
    }

    private record Staged(NativePathRequest request, Path path, boolean ordinary) {
    }

    private static final ThreadLocal<Staged> CURRENT = new ThreadLocal<>();
    /** Lazily created so a process that never binds a stage also never allocates the counter. */
    private static final class Hits {
        private static final AtomicLong VALUE = new AtomicLong();
    }

    /** The ordinary shadow comparisons and mismatches; both are lazy so no other mode pays for them. */
    private static final class Compared {
        private static final AtomicLong VALUE = new AtomicLong();
    }

    private static final class Mismatched {
        private static final AtomicLong VALUE = new AtomicLong();
    }

    private NativePathStage() {
    }

    public static void bind(NativePathRequest request, Path path) {
        bind(request, path, false);
    }

    /** Binds one staged search; an ordinary start prediction is one-shot: the first live find call of
     * the body consumes it whether or not the request matches, so no later call sees a stale one. */
    public static void bind(NativePathRequest request, Path path, boolean ordinary) {
        if (request == null) {
            throw new IllegalArgumentException("a staged search needs its frozen request");
        }
        CURRENT.set(new Staged(request, path, ordinary));
    }

    public static void unbind() {
        CURRENT.remove();
    }

    public static boolean bound() {
        return CURRENT.get() != null;
    }

    /** The staged searches the original call site has taken since this process started. Only an
     * enabled native path binds a stage, so an off process never increments this. */
    public static long hits() {
        return Hits.VALUE.get();
    }

    /** The ordinary shadow hits whose inline twin was compared. */
    public static long comparedOrdinary() {
        return Compared.VALUE.get();
    }

    /** The ordinary shadow hits whose inline twin was a different path. */
    public static long mismatchedOrdinary() {
        return Mismatched.VALUE.get();
    }

    /** Compares one shadow hit with the inline answer and counts an ordinary comparison; returns
     * whether both are the same search result. A null path is a real answer and compares as one. */
    public static boolean recordComparison(Hit hit, Path inline) {
        boolean same = samePath(hit.path(), inline);
        if (hit.ordinary()) {
            Compared.VALUE.incrementAndGet();
            if (!same) {
                Mismatched.VALUE.incrementAndGet();
            }
        }
        return same;
    }

    /** Takes the staged result when the live call names the same search; an ordinary stage is
     * consumed by the first call either way, a delayed stage only by a match. */
    public static Hit take(NativePathRequest.Live live) {
        Staged staged = CURRENT.get();
        if (staged == null) {
            return Hit.NONE;
        }
        if (!staged.request().matches(live)) {
            if (staged.ordinary()) {
                CURRENT.remove();
            }
            return Hit.NONE;
        }
        CURRENT.remove();
        Hits.VALUE.incrementAndGet();
        return new Hit(true, staged.path(), staged.ordinary());
    }

    private static boolean samePath(Path own, Path other) {
        if (own == null || other == null) {
            return own == other;
        }
        if (own.getNodeCount() != other.getNodeCount()) {
            return false;
        }
        if (!Objects.equals(own.getTarget(), other.getTarget()) || own.canReach() != other.canReach()) {
            return false;
        }
        for (int index = 0; index < own.getNodeCount(); index++) {
            Node mine = own.getNode(index);
            Node theirs = other.getNode(index);
            if (mine.x != theirs.x || mine.y != theirs.y || mine.z != theirs.z) {
                return false;
            }
        }
        return true;
    }
}
