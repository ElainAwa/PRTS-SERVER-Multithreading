/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec;

import net.minecraft.world.level.pathfinder.Path;

import java.util.concurrent.atomic.AtomicLong;

/** The staged search of the one row whose body runs on this thread; the first matching call takes it. */
public final class NativePathStage {

    /** The answer to one staged lookup: whether a staged search matched and the path it produced; a
     * null path is a real result and means the original search would have found nothing. */
    public record Hit(boolean matched, Path path) {

        public static final Hit NONE = new Hit(false, null);
    }

    private record Staged(NativePathRequest request, Path path) {
    }

    private static final ThreadLocal<Staged> CURRENT = new ThreadLocal<>();
    /** Lazily created so a process that never binds a stage also never allocates the counter. */
    private static final class Hits {
        private static final AtomicLong VALUE = new AtomicLong();
    }

    private NativePathStage() {
    }

    public static void bind(NativePathRequest request, Path path) {
        if (request == null) {
            throw new IllegalArgumentException("a staged search needs its frozen request");
        }
        CURRENT.set(new Staged(request, path));
    }

    public static void unbind() {
        CURRENT.remove();
    }

    public static boolean bound() {
        return CURRENT.get() != null;
    }

    /** The staged searches the original call site has taken since this process started. Only the
     * enabled native path binds a stage, so an off process never increments this. */
    public static long hits() {
        return Hits.VALUE.get();
    }

    /** Takes the staged result when the live call names the same search; consumed on a match. */
    public static Hit take(NativePathRequest.Live live) {
        Staged staged = CURRENT.get();
        if (staged == null) {
            return Hit.NONE;
        }
        if (!staged.request().matches(live)) {
            return Hit.NONE;
        }
        CURRENT.remove();
        Hits.VALUE.incrementAndGet();
        return new Hit(true, staged.path());
    }
}
