/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * A read-only census of the entity rows one tick offers to a takeover, and the host-side cost of
 * the rows it would replace. Every classification is derived from state the host already holds;
 * nothing here writes an entity, a world or a switch, and no reading takes part in a decision.
 */
package io.izzel.arclight.common.prts.support;

import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Counts what a tick offers and what a takeover would have to skip, and - when the process declares
 * {@code -Darclight.prts.entityCensus=true} - times the host tick and the movement segment of every
 * row per entity class. The timer is off by default, so a process that does not measure pays one
 * branch per call.
 */
public final class PrtsEntityCapability {

    /** The row may be taken over by a kinematics owner. */
    public static final int ELIGIBLE = 0;
    /** The row creates, removes or moves itself between worlds. */
    public static final int LIFECYCLE = 1;
    /** The row runs AI, target selection or pathfinding. */
    public static final int AI = 2;
    /** The row rides, is ridden or is leashed: its motion belongs to another entity. */
    public static final int RIDING = 3;
    /** The row pushes or collides with other entities. */
    public static final int CROSS_ENTITY = 4;
    /** The row dispatches an event, a platform hook or code outside the host. */
    public static final int HOST_CALLBACK = 5;
    /** The row states no capability this census can derive: it stays with the host. */
    public static final int UNKNOWN = 6;
    /** Not a candidate row at all; counted apart, never eligible. */
    public static final int PLAYER = 7;

    private static final int REASONS = 8;
    private static final int SLOTS = 64;
    private static final String OTHER_CLASS = "other";
    private static final boolean TIMED = Boolean.getBoolean("arclight.prts.entityCensus");

    private static final LongAdder CANDIDATES = new LongAdder();
    private static final LongAdder PLAYER_ROWS = new LongAdder();
    private static final LongAdder CAP_DROPPED = new LongAdder();
    private static final LongAdder NO_AI_MOBS = new LongAdder();
    private static final LongAdder TICK_OPENS = new LongAdder();
    private static final LongAdder TICK_CLOSES = new LongAdder();
    private static final LongAdder TICK_UNPAIRED = new LongAdder();
    private static final LongAdder TICK_CANCELLED = new LongAdder();
    private static final LongAdder MOVE_OPENS = new LongAdder();
    private static final LongAdder MOVE_CLOSES = new LongAdder();
    private static final LongAdder OWNER_CONFLICTS = new LongAdder();
    private static final LongAdder[] REASON_COUNTS = adders(REASONS);
    private static final LongAdder[] SLOT_ROWS = adders(SLOTS);
    private static final LongAdder[] SLOT_TICK_NANOS = adders(SLOTS);
    private static final LongAdder[] SLOT_TICK_COUNT = adders(SLOTS);
    private static final LongAdder[] SLOT_MOVE_NANOS = adders(SLOTS);
    private static final LongAdder[] SLOT_MOVE_COUNT = adders(SLOTS);
    private static final LongAdder[] SLOT_REASONS = adders(SLOTS * REASONS);
    private static final String[] SLOT_NAMES = new String[SLOTS];
    private static final AtomicInteger SLOTS_TAKEN = new AtomicInteger();
    /** Class to slot, computed once per class; the slot table is the contract of the export. */
    private static final ClassValue<Integer> SLOT = new ClassValue<>() {

        @Override
        protected Integer computeValue(Class<?> type) {
            int next = SLOTS_TAKEN.getAndIncrement();
            int slot = next < SLOTS - 1 ? next : SLOTS - 1;
            SLOT_NAMES[slot] = shortName(type);
            return slot;
        }
    };
    /** Class to the reason a row of it starts from; state-dependent reasons refine it per row. */
    private static final ClassValue<Integer> STATIC_REASON = new ClassValue<>() {

        @Override
        protected Integer computeValue(Class<?> type) {
            return classify(type);
        }
    };

    private PrtsEntityCapability() {
    }

    /** Counts one non-player row the frame froze, and the class it came from. */
    public static void census(Entity entity) {
        int reason = reasonOf(entity);
        int slot = slotOf(entity.getClass());
        CANDIDATES.increment();
        REASON_COUNTS[reason].increment();
        SLOT_ROWS[slot].increment();
        SLOT_REASONS[slot * REASONS + reason].increment();
        if (entity instanceof Mob mob && mob.isNoAi()) {
            NO_AI_MOBS.increment();
        }
        if (reason == RIDING) {
            OWNER_CONFLICTS.increment();
        }
    }

    /** Counts one player row the frame skipped. */
    public static void playerRow() {
        PLAYER_ROWS.increment();
    }

    /** Counts one non-player entity the per-world row cap left out of the frame. */
    public static void noteCapDropped() {
        CAP_DROPPED.increment();
    }

    /** Names the reason one row starts from; state refines the class-level answer. */
    public static int reasonOf(Entity entity) {
        Integer fixed = STATIC_REASON.get(entity.getClass());
        int reason = fixed == null ? UNKNOWN : fixed;
        if (reason == PLAYER) {
            return PLAYER;
        }
        if (entity.isRemoved()) {
            return LIFECYCLE;
        }
        if (entity.isPassenger() || entity.isVehicle()) {
            return RIDING;
        }
        if (entity instanceof Leashable leashable && leashable.isLeashed()) {
            return RIDING;
        }
        if (reason == AI) {
            if (entity instanceof Mob mob && !mob.isNoAi()) {
                // The host runs the goal selectors, the navigation and the sensing of this row.
                return AI;
            }
            // A mob that declares no AI skips serverAiStep, but it is still a living row: it pushes
            // and queries other entities every tick, so it carries the living-row reason.
            return entity.isPushable() ? CROSS_ENTITY : UNKNOWN;
        }
        if (reason == ELIGIBLE && entity.isPushable()) {
            return CROSS_ENTITY;
        }
        return reason;
    }

    /** Starts the host tick of one row; paired with {@link #endTick(Entity)}. */
    public static void beginTick(Entity entity) {
        if (!TIMED) {
            return;
        }
        TICK_OPENS.increment();
        Timer timer = TIMER.get();
        // A host hook that cancels at the head of the tick returns without reaching the return
        // hook, so the frame it left open is dropped here: it measured no work, and the next frame
        // of this thread starts from an empty stack.
        if (timer.drop(Timer.TICK)) {
            TICK_CANCELLED.increment();
        }
        timer.open(Timer.TICK, entity, slotOf(entity.getClass()));
    }

    /** Closes the host tick of one row and keeps its duration when the frame was not nested. */
    public static void endTick(Entity entity) {
        if (!TIMED) {
            return;
        }
        TICK_CLOSES.increment();
        Timer timer = TIMER.get();
        int depth = timer.close(Timer.TICK, entity);
        if (depth != 0) {
            TICK_UNPAIRED.increment();
            return;
        }
        int slot = timer.slot;
        SLOT_TICK_NANOS[slot].add(timer.nanos);
        SLOT_TICK_COUNT[slot].increment();
    }

    /** Starts the movement segment of one row; paired with {@link #endMove(Entity)}. */
    public static void beginMove(Entity entity) {
        if (!TIMED) {
            return;
        }
        MOVE_OPENS.increment();
        TIMER.get().open(Timer.MOVE, entity, slotOf(entity.getClass()));
    }

    /** Closes the movement segment of one row and keeps its duration. */
    public static void endMove(Entity entity) {
        if (!TIMED) {
            return;
        }
        MOVE_CLOSES.increment();
        Timer timer = TIMER.get();
        if (timer.close(Timer.MOVE, entity) < 0) {
            return;
        }
        int slot = timer.slot;
        SLOT_MOVE_NANOS[slot].add(timer.nanos);
        SLOT_MOVE_COUNT[slot].increment();
    }

    /** @return whether the timing arm is declared ({@code -Darclight.prts.entityCensus=true}) */
    public static boolean timed() {
        return TIMED;
    }

    /** How often the host tick hook opened, closed and failed to pair; diagnostics only. */
    public static long tickOpens() {
        return TICK_OPENS.sum();
    }

    public static long tickCloses() {
        return TICK_CLOSES.sum();
    }

    public static long tickUnpaired() {
        return TICK_UNPAIRED.sum();
    }

    /** Frames a cancelled host hook left open: the tick did not run, so it carries no time. */
    public static long tickCancelled() {
        return TICK_CANCELLED.sum();
    }

    public static long moveOpens() {
        return MOVE_OPENS.sum();
    }

    public static long moveCloses() {
        return MOVE_CLOSES.sum();
    }

    public static long candidates() {
        return CANDIDATES.sum();
    }

    public static long players() {
        return PLAYER_ROWS.sum();
    }

    public static long capDropped() {
        return CAP_DROPPED.sum();
    }

    public static long eligible() {
        return REASON_COUNTS[ELIGIBLE].sum();
    }

    public static long reasonCount(int reason) {
        return reason >= 0 && reason < REASONS ? REASON_COUNTS[reason].sum() : 0L;
    }

    public static long noAiMobs() {
        return NO_AI_MOBS.sum();
    }

    public static long ownerConflicts() {
        return OWNER_CONFLICTS.sum();
    }

    /** The host tick nanoseconds of the rows one class contributed, when the timer is declared. */
    public static long tickNanosOfClass(String name) {
        for (int slot = 0; slot < SLOTS; slot++) {
            if (name.equals(SLOT_NAMES[slot])) {
                return SLOT_TICK_NANOS[slot].sum();
            }
        }
        return 0L;
    }

    /** The movement nanoseconds of the rows one class contributed, when the timer is declared. */
    public static long moveNanosOfClass(String name) {
        for (int slot = 0; slot < SLOTS; slot++) {
            if (name.equals(SLOT_NAMES[slot])) {
                return SLOT_MOVE_NANOS[slot].sum();
            }
        }
        return 0L;
    }

    public static long tickCountOfClass(String name) {
        for (int slot = 0; slot < SLOTS; slot++) {
            if (name.equals(SLOT_NAMES[slot])) {
                return SLOT_TICK_COUNT[slot].sum();
            }
        }
        return 0L;
    }

    public static long moveCountOfClass(String name) {
        for (int slot = 0; slot < SLOTS; slot++) {
            if (name.equals(SLOT_NAMES[slot])) {
                return SLOT_MOVE_COUNT[slot].sum();
            }
        }
        return 0L;
    }

    /** One line with the census of the whole process, key=value, for the evidence logger. */
    public static String censusLine() {
        StringBuilder builder = new StringBuilder("[PRTS] entity-census: candidates=");
        builder.append(candidates()).append(" players=").append(players())
            .append(" cap_dropped=").append(capDropped())
            .append(" eligible=").append(eligible())
            .append(" no_ai_mobs=").append(noAiMobs())
            .append(" owner_conflict=").append(ownerConflicts())
            .append(" lifecycle=").append(reasonCount(LIFECYCLE))
            .append(" ai=").append(reasonCount(AI))
            .append(" riding=").append(reasonCount(RIDING))
            .append(" cross_entity=").append(reasonCount(CROSS_ENTITY))
            .append(" host_callback=").append(reasonCount(HOST_CALLBACK))
            .append(" unknown=").append(reasonCount(UNKNOWN))
            .append(" timed=").append(TIMED ? 1 : 0)
            .append(" tick_open=").append(tickOpens())
            .append(" tick_close=").append(tickCloses())
            .append(" tick_unpaired=").append(tickUnpaired())
            .append(" tick_cancelled=").append(tickCancelled())
            .append(" move_open=").append(moveOpens())
            .append(" move_close=").append(moveCloses());
        return builder.toString();
    }

    /** One line with the rows, reason and host cost of every class the frame carried. */
    public static String classLine() {
        StringBuilder builder = new StringBuilder("[PRTS] entity-class:");
        for (int slot : busiestSlots()) {
            String name = SLOT_NAMES[slot];
            if (name == null) {
                continue;
            }
            builder.append(' ').append(name).append('=')
                .append(SLOT_ROWS[slot].sum()).append(',')
                .append(dominantReason(slot)).append(',')
                .append(SLOT_TICK_NANOS[slot].sum()).append(',')
                .append(SLOT_TICK_COUNT[slot].sum()).append(',')
                .append(SLOT_MOVE_NANOS[slot].sum()).append(',')
                .append(SLOT_MOVE_COUNT[slot].sum());
        }
        return builder.toString();
    }

    /** Clears every counter; the readout reset and the tests use it. */
    public static void reset() {
        CANDIDATES.reset();
        PLAYER_ROWS.reset();
        CAP_DROPPED.reset();
        NO_AI_MOBS.reset();
        OWNER_CONFLICTS.reset();
        TICK_OPENS.reset();
        TICK_CLOSES.reset();
        TICK_UNPAIRED.reset();
        TICK_CANCELLED.reset();
        MOVE_OPENS.reset();
        MOVE_CLOSES.reset();
        for (int index = 0; index < REASONS; index++) {
            REASON_COUNTS[index].reset();
        }
        for (int slot = 0; slot < SLOTS; slot++) {
            SLOT_ROWS[slot].reset();
            SLOT_TICK_NANOS[slot].reset();
            SLOT_TICK_COUNT[slot].reset();
            SLOT_MOVE_NANOS[slot].reset();
            SLOT_MOVE_COUNT[slot].reset();
            for (int reason = 0; reason < REASONS; reason++) {
                SLOT_REASONS[slot * REASONS + reason].reset();
            }
        }
    }

    /** The class-level reason a row starts from; the per-row refinement stays in reasonOf. */
    private static int classify(Class<?> type) {
        if (Player.class.isAssignableFrom(type)) {
            return PLAYER;
        }
        if (!type.getName().startsWith("net.minecraft.")) {
            // Code that is not the host can attach a callback a frozen row cannot answer for.
            return HOST_CALLBACK;
        }
        if (Mob.class.isAssignableFrom(type)) {
            return AI;
        }
        if (ItemEntity.class.isAssignableFrom(type)) {
            // The host tick of an item calls the item's own update hook and, on expiry, an event.
            return HOST_CALLBACK;
        }
        if (Projectile.class.isAssignableFrom(type)) {
            // A projectile answers a hit: it damages, it can raise an event and it removes itself.
            return HOST_CALLBACK;
        }
        if (ExperienceOrb.class.isAssignableFrom(type)) {
            // An orb steers to the nearest player, which is a read of another entity every tick.
            return CROSS_ENTITY;
        }
        if (LivingEntity.class.isAssignableFrom(type)) {
            // The living tick always pushes nearby entities and queries the world for them.
            return CROSS_ENTITY;
        }
        if (Marker.class.isAssignableFrom(type) || Interaction.class.isAssignableFrom(type)
            || Display.class.isAssignableFrom(type) || HangingEntity.class.isAssignableFrom(type)
            || LeashFenceKnotEntity.class.isAssignableFrom(type)) {
            // These rows hold a position and an orientation only; the host moves them never.
            return ELIGIBLE;
        }
        return UNKNOWN;
    }

    private static int slotOf(Class<?> type) {
        Integer slot = SLOT.get(type);
        return slot == null ? SLOTS - 1 : slot;
    }

    private static String shortName(Class<?> type) {
        String name = type.getName();
        int dot = name.lastIndexOf('.');
        String simple = dot < 0 ? name : name.substring(dot + 1);
        return simple.length() > 40 ? simple.substring(0, 40) : simple;
    }

    private static int dominantReason(int slot) {
        int best = UNKNOWN;
        long bestCount = -1L;
        for (int reason = 0; reason < REASONS; reason++) {
            long count = SLOT_REASONS[slot * REASONS + reason].sum();
            if (count > bestCount) {
                bestCount = count;
                best = reason;
            }
        }
        return best;
    }

    private static List<Integer> busiestSlots() {
        List<Integer> slots = new ArrayList<>();
        for (int slot = 0; slot < SLOTS; slot++) {
            if (SLOT_NAMES[slot] != null && SLOT_ROWS[slot].sum() > 0L) {
                slots.add(slot);
            }
        }
        slots.sort(Comparator.comparingLong((Integer slot) -> -SLOT_ROWS[slot].sum()));
        return slots.size() > 16 ? slots.subList(0, 16) : slots;
    }

    private static LongAdder[] adders(int count) {
        LongAdder[] adders = new LongAdder[count];
        for (int index = 0; index < count; index++) {
            adders[index] = new LongAdder();
        }
        return adders;
    }

    private static final ThreadLocal<Timer> TIMER = ThreadLocal.withInitial(Timer::new);

    /** One open frame per phase and thread; the slot names the class the frame is charged to. */
    private static final class Timer {

        private static final int TICK = 0;
        private static final int MOVE = 1;
        private static final int PHASES = 2;
        private static final int DEPTH = 32;

        private final long[][] start = new long[PHASES][DEPTH];
        private final Entity[][] owner = new Entity[PHASES][DEPTH];
        private final int[][] slotOf = new int[PHASES][DEPTH];
        private final int[] depth = new int[PHASES];
        private int slot;
        private long nanos;

        private void open(int phase, Entity entity, int slot) {
            int at = depth[phase];
            if (at >= DEPTH) {
                // A frame this deep cannot be paired; the observation is dropped, not guessed.
                return;
            }
            start[phase][at] = System.nanoTime();
            owner[phase][at] = entity;
            slotOf[phase][at] = slot;
            depth[phase] = at + 1;
        }

        /** Drops a frame that never returned. @return whether a frame was open */
        private boolean drop(int phase) {
            int at = depth[phase] - 1;
            if (at < 0) {
                return false;
            }
            depth[phase] = at;
            owner[phase][at] = null;
            return true;
        }

        /** @return the depth the closed frame sat at, or -1 when the frame could not be paired */
        private int close(int phase, Entity entity) {
            int at = depth[phase] - 1;
            if (at < 0 || owner[phase][at] != entity) {
                return -1;
            }
            depth[phase] = at;
            owner[phase][at] = null;
            nanos = System.nanoTime() - start[phase][at];
            slot = slotOf[phase][at];
            return at;
        }
    }
}