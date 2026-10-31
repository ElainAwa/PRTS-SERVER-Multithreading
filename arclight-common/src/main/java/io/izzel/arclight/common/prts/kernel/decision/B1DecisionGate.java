/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.decision;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** The B1 decision gate: a real overrun arms one world/token, then each eligible goal-selector
 * decision cycle may suppress the new-goal selection at most once per two cycles. Cleanup, running
 * goals, navigation and movement are outside this gate. State is owned by the server thread and
 * every entry point is synchronized; it publishes no readout key. */
public final class B1DecisionGate {

    /** One decision answer; the first real skip carries the armed token. */
    public record Decision(boolean suppress, long token, boolean firstSkip, boolean eligible) {
    }

    private record Key(String worldId, UUID entityId) {
    }

    private static final class EntityState {
        long calls;
        boolean skippedLast;
        boolean applied;
    }

    private static final class WorldArm {
        final long token;
        final long generation;

        WorldArm(long token, long generation) {
            this.token = token;
            this.generation = generation;
        }
    }

    /** Eligibility: a goal-selector mob whose brain is not running an activity. */
    public static boolean eligible(boolean brainActive, boolean hasGoals) {
        return !brainActive && hasGoals;
    }

    private static final ThreadLocal<Boolean> SUPPRESS = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final Map<String, WorldArm> ARMED = new HashMap<>();
    private static final Map<Key, EntityState> STATES = new HashMap<>();
    private static long eligibleCount;
    private static long skippedCount;
    private static long forwardedCount;

    private B1DecisionGate() {
    }

    /** Arms one world for the B1 action; a newer generation replaces the older arm. */
    public static synchronized void arm(String worldId, long token, long generation) {
        if (worldId == null || worldId.isBlank() || token <= 0L) {
            return;
        }
        ARMED.put(worldId, new WorldArm(token, generation));
        STATES.keySet().removeIf(key -> key.worldId().equals(worldId));
    }

    /** Clears an arm that never produced a real skip within the declared age. */
    public static synchronized void clearStale(long currentTick, long maxAgeTicks) {
        long age = Math.max(0L, maxAgeTicks);
        ARMED.entrySet().removeIf(entry -> currentTick - entry.getValue().generation > age);
    }

    public static synchronized void disarm(String worldId) {
        if (worldId == null) {
            return;
        }
        ARMED.remove(worldId);
        STATES.keySet().removeIf(key -> key.worldId().equals(worldId));
    }

    /** One GoalSelector decision cycle for one eligible entity. */
    public static synchronized Decision decide(String worldId, UUID entityId, boolean eligible) {
        if (!eligible || worldId == null || entityId == null) {
            return new Decision(false, 0L, false, false);
        }
        eligibleCount++;
        WorldArm arm = ARMED.get(worldId);
        if (arm == null) {
            forwardedCount++;
            return new Decision(false, 0L, false, true);
        }
        EntityState state = STATES.computeIfAbsent(new Key(worldId, entityId),
            ignored -> new EntityState());
        boolean suppress = (state.calls % 2L) == 1L && !state.skippedLast;
        state.calls++;
        if (!suppress) {
            state.skippedLast = false;
            forwardedCount++;
            return new Decision(false, 0L, false, true);
        }
        state.skippedLast = true;
        skippedCount++;
        boolean first = !state.applied;
        state.applied = true;
        return new Decision(true, first ? arm.token : 0L, first, true);
    }

    /** Marks the current thread as inside the one goal-selector tick whose new-goal selection is
     * deferred; cleanup and running goals still run. */
    public static void beginSuppress() {
        SUPPRESS.set(Boolean.TRUE);
    }

    public static void endSuppress() {
        SUPPRESS.remove();
    }

    public static boolean suppressed() {
        return Boolean.TRUE.equals(SUPPRESS.get());
    }

    public static synchronized long eligibleCount() {
        return eligibleCount;
    }

    public static synchronized long skippedCount() {
        return skippedCount;
    }

    public static synchronized long forwardedCount() {
        return forwardedCount;
    }

    public static synchronized java.util.List<String> rows() {
        return java.util.List.of("ai.decision_eligible=" + eligibleCount,
            "ai.decision_skipped=" + skippedCount,
            "ai.decision_forwarded=" + forwardedCount);
    }

    public static synchronized void reset() {
        ARMED.clear();
        STATES.clear();
        eligibleCount = 0L;
        skippedCount = 0L;
        forwardedCount = 0L;
    }
}
