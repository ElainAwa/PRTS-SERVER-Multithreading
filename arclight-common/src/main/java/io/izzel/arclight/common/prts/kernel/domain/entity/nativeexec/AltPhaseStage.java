/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Staged intermediate results of E3/E7/E9 for one owner row. A result is consumed exactly once by
 * its original write-back point; a second take returns null so no value can be applied twice. */
public final class AltPhaseStage {

    private static final Map<String, Object> READY = new ConcurrentHashMap<>();
    private static final ThreadLocal<Map<String, Object>> CURRENT = new ThreadLocal<>();

    private AltPhaseStage() {
    }

    /** Publishes one worker result under rowKey and kind. */
    public static void stage(String rowKey, String kind, Object result) {
        if (result != null) READY.put(rowKey + "|" + kind, result);
    }

    /** Moves one row's staged results onto the Server-thread seat. */
    public static void bind(String rowKey) {
        Map<String, Object> bound = new java.util.HashMap<>();
        for (String kind : new String[]{"aiHead", "controls", "tickTail"}) {
            Object value = READY.remove(rowKey + "|" + kind);
            if (value != null) bound.put(kind, value);
        }
        if (!bound.isEmpty()) CURRENT.set(bound);
    }

    /** Takes and consumes one staged result; the write-back point is single by construction. */
    @SuppressWarnings("unchecked")
    public static <T> T take(String kind) {
        Map<String, Object> bound = CURRENT.get();
        if (bound == null || !bound.containsKey(kind)) return null;
        Object value = bound.remove(kind);
        if (bound.isEmpty()) CURRENT.remove();
        return (T) value;
    }

    /** Ends one seat; unconsumed results are dropped, never carried to another row. */
    public static void unbind() {
        CURRENT.remove();
    }
}