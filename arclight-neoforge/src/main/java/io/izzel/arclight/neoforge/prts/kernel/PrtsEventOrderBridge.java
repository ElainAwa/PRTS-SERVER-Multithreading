/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.neoforge.prts.kernel;

import io.izzel.arclight.common.prts.kernel.observe.EventOrderProbe;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayDeque;

/** The one narrow bridge from the platform bus to the common event face: it names the three root
 * events, hands the frozen dispatch list of each post to the probe and answers the three frame calls
 * the read-only bus mixin makes. It keeps no event, player or level reference. */
public final class PrtsEventOrderBridge {

    private static final Object INACTIVE = new Object();

    private static final ThreadLocal<ArrayDeque<Object>> FRAMES =
        ThreadLocal.withInitial(ArrayDeque::new);

    private PrtsEventOrderBridge() {
    }

    /** Installs the common probe face; a process whose property is off keeps it disabled. */
    public static void install() {
        EventOrderProbe.install();
    }

    /** Removes the common probe face and drops the readings of the old generation. */
    public static void remove() {
        EventOrderProbe.uninstall();
    }

    /** Whether the probe is installed and the property is set. */
    public static boolean active() {
        return EventOrderProbe.enabled();
    }

    /** Opens the frame of one post; the token stack pairs it with {@link #endDispatch(boolean)}. */
    public static void beginDispatch(Object bus, Object event, Object[] expected) {
        Object token = active() ? EventOrderProbe.begin(bus, expected, rootEvent(event)) : null;
        FRAMES.get().push(token == null ? INACTIVE : token);
    }

    /** Records one real listener invocation of the newest frame on this thread. */
    public static void noteInvocation(Object listener) {
        EventOrderProbe.invokedCurrent(listener);
    }

    /** Closes the newest post frame of this thread and reports whether the dispatch failed. */
    public static void endDispatch(boolean failed) {
        ArrayDeque<Object> frames = FRAMES.get();
        if (frames.isEmpty()) {
            return;
        }
        Object token = frames.pop();
        if (frames.isEmpty()) {
            FRAMES.remove();
        }
        if (token != INACTIVE) {
            EventOrderProbe.end(token, failed);
        }
    }

    static boolean rootEvent(Object event) {
        return event instanceof ServerTickEvent.Pre
            || event instanceof ServerTickEvent.Post
            || event instanceof LevelEvent.Unload;
    }
}
