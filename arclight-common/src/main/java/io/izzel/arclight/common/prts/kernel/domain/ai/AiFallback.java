/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.ai;

/** Internal no-decision reasons for the AI first slice; a skip is visible and counted. */
public final class AiFallback {

    public enum Reason {
        CAPTURE_FAILED, STALE, UNLOADED, CANCELLED, LATE
    }

    public record Skip(long actorId, Reason reason) {
    }

    private AiFallback() {
    }

    public static Skip of(long actorId, Reason reason) {
        if (reason == null) throw new IllegalArgumentException("fallback reason is required");
        return new Skip(actorId, reason);
    }
}
