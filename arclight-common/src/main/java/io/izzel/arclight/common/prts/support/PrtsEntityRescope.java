/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * A read-only re-scoping census of the rows the host entry really ticks. It answers two questions
 * the earlier snapshot-side census could not: how many rows reach the point where a takeover would
 * decide (the denominator of a coverage ratio), and how long the whole tick of such a row lasts
 * (the surface a skip at that point can remove). The predicates only read host state; every count
 * and every duration is observation, off unless the process asks for it, and no reading takes part
 * in a decision.
 */
package io.izzel.arclight.common.prts.support;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.GlowSquid;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.animal.Squid;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Counts, per entity class, how many rows reached the host tick entry point, which narrowed
 * predicate still excluded them, and how long their whole tick ran. The arm is declared with
 * {@code -Darclight.prts.entityRescope=true} and is off otherwise, so a process that does not
 * measure pays one branch per row.
 */
public final class PrtsEntityRescope {

    /** The whole tick of this row looks reproducible from frozen host state alone. */
    public static final int WIDENED = 0;
    /** The class is not the host's: taking it over would take over foreign code. */
    public static final int THIRD_PARTY = 1;
    /** A player row: it is never a takeover row. */
    public static final int PLAYER = 2;
    /** The row is being removed. */
    public static final int LIFECYCLE = 3;
    /** The row rides, is ridden or is leashed: its motion belongs to another entity. */
    public static final int RIDING = 4;
    /** The row runs goal selectors, navigation or sensing. */
    public static final int AI = 5;
    /** The row runs an item, projectile or platform callback inside its tick. */
    public static final int CALLBACK = 6;
    /** The row has pushable neighbours, whose motion it shares. */
    public static final int NEIGHBOURS = 7;
    /** The row sits in a fluid whose state decides its motion. */
    public static final int FLUID = 8;
    /** The row is on fire. */
    public static final int FIRE = 9;
    /** The row states no reproducible whole tick this census can derive. */
    public static final int UNMODELED = 10;

    private static final int VERDICTS = 11;
    private static final int SLOTS = 64;
    private static final int DEPTH = 8;
    /** The tag the load generator puts on every entity it summons, so declared rows count apart. */
    private static final String LOAD_TAG = "cal_load";
    // The classes a controlled takeover may claim; a lookup here never creates a class slot, so a
    // reader off the tick thread cannot race the census.
    private static final Class<?>[] WHITELIST = {ArmorStand.class, Villager.class, Bat.class,
        Marker.class, AreaEffectCloud.class};

    private static final long F_PLAYER = 1L;
    private static final long F_THIRD_PARTY = 1L << 1;
    private static final long F_LIFECYCLE = 1L << 2;
    private static final long F_RIDING = 1L << 3;
    private static final long F_AI = 1L << 4;
    private static final long F_CALLBACK = 1L << 5;
    private static final long F_UNMODELED = 1L << 6;
    private static final long F_NEIGHBOURS = 1L << 7;
    private static final long F_FLUID = 1L << 8;
    private static final long F_FIRE = 1L << 9;
    private static final long F_BASE = F_PLAYER | F_THIRD_PARTY | F_LIFECYCLE | F_RIDING | F_AI
        | F_CALLBACK | F_UNMODELED;

    private static final boolean TIMED = Boolean.getBoolean("arclight.prts.entityRescope");

    private static final LongAdder OFFERED = new LongAdder();
    private static final LongAdder ENTERED = new LongAdder();
    private static final LongAdder PASSENGERS = new LongAdder();
    private static final LongAdder PREDICATE_CALLS = new LongAdder();
    private static final LongAdder PREDICATE_NANOS = new LongAdder();
    private static final LongAdder UNPAIRED = new LongAdder();
    private static final LongAdder[] VERDICT_COUNTS = adders(VERDICTS);
    private static final LongAdder[] SLOT_OFFERED = adders(SLOTS);
    private static final LongAdder[] SLOT_PASSENGERS = adders(SLOTS);
    private static final LongAdder[] SLOT_ROWS = adders(SLOTS);
    private static final LongAdder[] SLOT_DECLARED = adders(SLOTS);
    private static final LongAdder[] SLOT_NO_AI = adders(SLOTS);
    private static final LongAdder[] SLOT_WITH_AI = adders(SLOTS);
    private static final LongAdder[] SLOT_WIDENED = adders(SLOTS);
    private static final LongAdder[] SLOT_WIDENED_DECLARED = adders(SLOTS);
    private static final LongAdder[] SLOT_INNER_NANOS = adders(SLOTS);
    private static final LongAdder[] SLOT_INNER_COUNT = adders(SLOTS);
    private static final LongAdder[] SLOT_WIDENED_NANOS = adders(SLOTS);
    private static final LongAdder[] SLOT_WIDENED_COUNT = adders(SLOTS);
    private static final LongAdder[] SLOT_NO_AI_NANOS = adders(SLOTS);
    private static final LongAdder[] SLOT_NO_AI_COUNT = adders(SLOTS);
    private static final LongAdder[] SLOT_WITH_AI_NANOS = adders(SLOTS);
    private static final LongAdder[] SLOT_WITH_AI_COUNT = adders(SLOTS);
    private static final LongAdder[] SLOT_ONLY_NEIGHBOURS = adders(SLOTS);
    private static final LongAdder[] SLOT_ONLY_FLUID = adders(SLOTS);
    private static final LongAdder[] SLOT_ONLY_FIRE = adders(SLOTS);
    private static final LongAdder[] SLOT_VERDICTS = adders(SLOTS * VERDICTS);
    private static final String[] SLOT_NAMES = new String[SLOTS];
    private static final AtomicInteger SLOTS_TAKEN = new AtomicInteger();
    private static final ClassValue<Integer> SLOT = new ClassValue<>() {

        @Override
        protected Integer computeValue(Class<?> type) {
            int next = SLOTS_TAKEN.getAndIncrement();
            int slot = next < SLOTS - 1 ? next : SLOTS - 1;
            SLOT_NAMES[slot] = shortName(type);
            return slot;
        }
    };

    private PrtsEntityRescope() {
    }

    /** @return whether the re-scoping arm is declared ({@code -Darclight.prts.entityRescope=true}) */
    public static boolean timed() {
        return TIMED;
    }

    /** Counts one row the host entry offered, before any cancellation of that row. */
    public static void rowOffered(Entity entity) {
        if (!TIMED) {
            return;
        }
        OFFERED.increment();
        SLOT_OFFERED[slotOf(entity.getClass())].increment();
    }

    /** Counts one row the host entry reached with a tick to run, and classifies it. */
    public static void hostEntry(ServerLevel level, Entity entity) {
        if (!TIMED) {
            return;
        }
        ENTERED.increment();
        int slot = slotOf(entity.getClass());
        SLOT_ROWS[slot].increment();
        boolean declared = entity.getTags().contains(LOAD_TAG);
        if (declared) {
            SLOT_DECLARED[slot].increment();
        }
        boolean noAi = false;
        if (entity instanceof Mob mob) {
            noAi = mob.isNoAi();
            if (noAi) {
                SLOT_NO_AI[slot].increment();
            } else {
                SLOT_WITH_AI[slot].increment();
            }
        }
        long opened = System.nanoTime();
        long flags = flagsOf(level, entity, noAi);
        long closed = System.nanoTime();
        PREDICATE_CALLS.increment();
        PREDICATE_NANOS.add(closed - opened);
        int verdict = verdictOf(flags);
        VERDICT_COUNTS[verdict].increment();
        SLOT_VERDICTS[slot * VERDICTS + verdict].increment();
        if ((flags & F_BASE) == 0L) {
            // The row is a modeled, idle one; what is left is the sensitivity of the ratio to the
            // three predicates a segment or region model could still freeze.
            if ((flags & F_NEIGHBOURS) != 0L && (flags & (F_FLUID | F_FIRE)) == 0L) {
                SLOT_ONLY_NEIGHBOURS[slot].increment();
            } else if ((flags & F_FLUID) != 0L && (flags & (F_NEIGHBOURS | F_FIRE)) == 0L) {
                SLOT_ONLY_FLUID[slot].increment();
            } else if ((flags & F_FIRE) != 0L && (flags & (F_NEIGHBOURS | F_FLUID)) == 0L) {
                SLOT_ONLY_FIRE[slot].increment();
            }
        }
        if (verdict == WIDENED) {
            SLOT_WIDENED[slot].increment();
            if (declared) {
                SLOT_WIDENED_DECLARED[slot].increment();
            }
        }
        // The frame opens after the predicate work, so the duration below is the host tick itself.
        ROW.get().open(entity, slot, verdict, noAi, closed);
    }

    /** Closes the host tick of one row and charges its whole duration to the class and verdict. */
    public static void hostExit(Entity entity) {
        if (!TIMED) {
            return;
        }
        Frame frame = ROW.get().close(entity);
        if (frame == null) {
            UNPAIRED.increment();
            return;
        }
        long nanos = System.nanoTime() - frame.start;
        SLOT_INNER_NANOS[frame.slot].add(nanos);
        SLOT_INNER_COUNT[frame.slot].increment();
        if (frame.noAi) {
            SLOT_NO_AI_NANOS[frame.slot].add(nanos);
            SLOT_NO_AI_COUNT[frame.slot].increment();
        } else if (frame.mob) {
            SLOT_WITH_AI_NANOS[frame.slot].add(nanos);
            SLOT_WITH_AI_COUNT[frame.slot].increment();
        }
        if (frame.verdict == WIDENED) {
            SLOT_WIDENED_NANOS[frame.slot].add(nanos);
            SLOT_WIDENED_COUNT[frame.slot].increment();
        }
    }

    /** Counts one row that rides another: the host ticks it without firing the entity tick event. */
    public static void passengerRow(Entity entity) {
        if (!TIMED) {
            return;
        }
        PASSENGERS.increment();
        SLOT_PASSENGERS[slotOf(entity.getClass())].increment();
    }

    /** A row count per verdict, one line, for the evidence logger. */
    public static String censusLine() {
        return "[PRTS] entity-rescope: offered=" + OFFERED.sum()
            + " entered=" + ENTERED.sum()
            + " passengers=" + PASSENGERS.sum()
            + " not_entered=" + (OFFERED.sum() - ENTERED.sum())
            + " predicate_calls=" + PREDICATE_CALLS.sum()
            + " predicate_ns=" + PREDICATE_NANOS.sum()
            + " unpaired=" + UNPAIRED.sum()
            + " widened=" + VERDICT_COUNTS[WIDENED].sum()
            + " third_party=" + VERDICT_COUNTS[THIRD_PARTY].sum()
            + " player=" + VERDICT_COUNTS[PLAYER].sum()
            + " lifecycle=" + VERDICT_COUNTS[LIFECYCLE].sum()
            + " riding=" + VERDICT_COUNTS[RIDING].sum()
            + " ai=" + VERDICT_COUNTS[AI].sum()
            + " callback=" + VERDICT_COUNTS[CALLBACK].sum()
            + " neighbours=" + VERDICT_COUNTS[NEIGHBOURS].sum()
            + " fluid=" + VERDICT_COUNTS[FLUID].sum()
            + " fire=" + VERDICT_COUNTS[FIRE].sum()
            + " unmodeled=" + VERDICT_COUNTS[UNMODELED].sum()
            + " timed=" + (TIMED ? 1 : 0);
    }

    /** One line with the classes the host entry ticked, their verdict and their whole-tick cost. */
    public static String classLine() {
        StringBuilder builder = new StringBuilder("[PRTS] entity-rescope-class:");
        for (int slot : busiestSlots()) {
            builder.append(' ').append(SLOT_NAMES[slot]).append('=')
                .append(SLOT_ROWS[slot].sum()).append(',')
                .append(dominantVerdict(slot)).append(',')
                .append(SLOT_DECLARED[slot].sum()).append(',')
                .append(SLOT_NO_AI[slot].sum()).append(',')
                .append(SLOT_WITH_AI[slot].sum()).append(',')
                .append(SLOT_WIDENED[slot].sum()).append(',')
                .append(SLOT_WIDENED_DECLARED[slot].sum()).append(',')
                .append(SLOT_INNER_NANOS[slot].sum()).append(',')
                .append(SLOT_INNER_COUNT[slot].sum()).append(',')
                .append(SLOT_WIDENED_NANOS[slot].sum()).append(',')
                .append(SLOT_WIDENED_COUNT[slot].sum()).append(',')
                .append(SLOT_NO_AI_NANOS[slot].sum()).append(',')
                .append(SLOT_NO_AI_COUNT[slot].sum()).append(',')
                .append(SLOT_WITH_AI_NANOS[slot].sum()).append(',')
                .append(SLOT_WITH_AI_COUNT[slot].sum()).append(',')
                .append(SLOT_ONLY_NEIGHBOURS[slot].sum()).append(',')
                .append(SLOT_ONLY_FLUID[slot].sum()).append(',')
                .append(SLOT_ONLY_FIRE[slot].sum()).append(',')
                .append(SLOT_OFFERED[slot].sum()).append(',')
                .append(SLOT_PASSENGERS[slot].sum());
        }
        return builder.toString();
    }

    /** The host whole-tick nanoseconds per row this process measured on the rows of one whitelisted
     * class that the takeover did not widen, or zero when the class was measured not at all or only
     * through widened rows. It is the same-run estimate of what skipping such a row removes; the
     * reading is observation only, no decision reads it, and the controlled pairing takes the cost
     * from the arm that runs every row on the host. */
    public static double hostNanosPerRowNotWidened(Class<?> type) {
        for (int slot = 0; slot < WHITELIST.length; slot++) {
            if (WHITELIST[slot] != type) {
                continue;
            }
            long rows = SLOT_INNER_COUNT[slot].sum() - SLOT_WIDENED_COUNT[slot].sum();
            long nanos = SLOT_INNER_NANOS[slot].sum() - SLOT_WIDENED_NANOS[slot].sum();
            return rows <= 0L || nanos <= 0L ? 0.0 : (double) nanos / (double) rows;
        }
        return 0.0;
    }

    /** One line with the verdict of every class, so an exclusion is attributable per class. */
    public static String reasonLine() {
        StringBuilder builder = new StringBuilder("[PRTS] entity-rescope-reason:");
        for (int slot : busiestSlots()) {
            builder.append(' ').append(SLOT_NAMES[slot]).append('=');
            for (int verdict = 0; verdict < VERDICTS; verdict++) {
                if (verdict > 0) {
                    builder.append(',');
                }
                builder.append(SLOT_VERDICTS[slot * VERDICTS + verdict].sum());
            }
        }
        return builder.toString();
    }

    /** Clears every counter; the readout reset and the tests use it. */
    public static void reset() {
        OFFERED.reset();
        ENTERED.reset();
        PASSENGERS.reset();
        PREDICATE_CALLS.reset();
        PREDICATE_NANOS.reset();
        UNPAIRED.reset();
        for (int index = 0; index < VERDICTS; index++) {
            VERDICT_COUNTS[index].reset();
        }
        for (int slot = 0; slot < SLOTS; slot++) {
            SLOT_OFFERED[slot].reset();
            SLOT_PASSENGERS[slot].reset();
            SLOT_ROWS[slot].reset();
            SLOT_DECLARED[slot].reset();
            SLOT_NO_AI[slot].reset();
            SLOT_WITH_AI[slot].reset();
            SLOT_WIDENED[slot].reset();
            SLOT_WIDENED_DECLARED[slot].reset();
            SLOT_INNER_NANOS[slot].reset();
            SLOT_INNER_COUNT[slot].reset();
            SLOT_WIDENED_NANOS[slot].reset();
            SLOT_WIDENED_COUNT[slot].reset();
            SLOT_NO_AI_NANOS[slot].reset();
            SLOT_NO_AI_COUNT[slot].reset();
            SLOT_WITH_AI_NANOS[slot].reset();
            SLOT_WITH_AI_COUNT[slot].reset();
            SLOT_ONLY_NEIGHBOURS[slot].reset();
            SLOT_ONLY_FLUID[slot].reset();
            SLOT_ONLY_FIRE[slot].reset();
            for (int verdict = 0; verdict < VERDICTS; verdict++) {
                SLOT_VERDICTS[slot * VERDICTS + verdict].reset();
            }
        }
    }

    /** The state a whole-tick takeover would have to reproduce, read without writing anything. */
    private static long flagsOf(ServerLevel level, Entity entity, boolean noAi) {
        long flags = 0L;
        if (entity instanceof Player) {
            flags |= F_PLAYER;
        }
        if (!entity.getClass().getName().startsWith("net.minecraft.")) {
            flags |= F_THIRD_PARTY;
        }
        if (entity.isRemoved()) {
            flags |= F_LIFECYCLE;
        }
        if (entity.isPassenger() || entity.isVehicle()
            || (entity instanceof Leashable leashable && leashable.isLeashed())) {
            flags |= F_RIDING;
        }
        if (entity instanceof Mob && !noAi) {
            flags |= F_AI;
        }
        if (entity instanceof ItemEntity || entity instanceof Projectile) {
            flags |= F_CALLBACK;
        }
        if (!modeled(entity)) {
            flags |= F_UNMODELED;
        }
        if (entity.isOnFire()) {
            flags |= F_FIRE;
        }
        if (entity.isInWater() || entity.isInLava() || entity.isInPowderSnow) {
            flags |= F_FLUID;
        }
        if (level.getEntities(entity, entity.getBoundingBox(), EntitySelector.pushableBy(entity))
            .isEmpty()) {
            return flags;
        }
        return flags | F_NEIGHBOURS;
    }

    /** Whether a whole tick of this row has a model this census is willing to name. */
    private static boolean modeled(Entity entity) {
        if (entity instanceof Villager || entity instanceof Squid || entity instanceof GlowSquid
            || entity instanceof Bat || entity instanceof ArmorStand || entity instanceof Marker
            || entity instanceof AreaEffectCloud) {
            return true;
        }
        // A falling block that rests on the ground writes a block and can raise a callback; only the
        // airborne row has a tick that reads the world without writing it.
        return entity instanceof FallingBlockEntity && !entity.onGround();
    }

    private static int verdictOf(long flags) {
        if ((flags & F_PLAYER) != 0L) {
            return PLAYER;
        }
        if ((flags & F_THIRD_PARTY) != 0L) {
            return THIRD_PARTY;
        }
        if ((flags & F_LIFECYCLE) != 0L) {
            return LIFECYCLE;
        }
        if ((flags & F_RIDING) != 0L) {
            return RIDING;
        }
        if ((flags & F_AI) != 0L) {
            return AI;
        }
        if ((flags & F_CALLBACK) != 0L) {
            return CALLBACK;
        }
        if ((flags & F_UNMODELED) != 0L) {
            return UNMODELED;
        }
        if ((flags & F_NEIGHBOURS) != 0L) {
            return NEIGHBOURS;
        }
        if ((flags & F_FLUID) != 0L) {
            return FLUID;
        }
        if ((flags & F_FIRE) != 0L) {
            return FIRE;
        }
        return WIDENED;
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

    private static int dominantVerdict(int slot) {
        int best = UNMODELED;
        long bestCount = -1L;
        for (int verdict = 0; verdict < VERDICTS; verdict++) {
            long count = SLOT_VERDICTS[slot * VERDICTS + verdict].sum();
            if (count > bestCount) {
                bestCount = count;
                best = verdict;
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

    private static final ThreadLocal<Row> ROW = ThreadLocal.withInitial(Row::new);

    /** One open host tick per thread, paired by entity so a lost frame is dropped, not guessed. */
    private static final class Row {

        private final Entity[] owner = new Entity[DEPTH];
        private final long[] start = new long[DEPTH];
        private final int[] slot = new int[DEPTH];
        private final int[] verdict = new int[DEPTH];
        private final boolean[] noAi = new boolean[DEPTH];
        private final boolean[] mob = new boolean[DEPTH];
        private int depth;

        private void open(Entity entity, int slotOfRow, int verdictOfRow, boolean noAiOfRow,
            long startedAt) {
            int at = depth;
            if (at >= DEPTH) {
                return;
            }
            owner[at] = entity;
            start[at] = startedAt;
            slot[at] = slotOfRow;
            verdict[at] = verdictOfRow;
            noAi[at] = noAiOfRow;
            mob[at] = entity instanceof Mob;
            depth = at + 1;
        }

        private Frame close(Entity entity) {
            int at = depth - 1;
            if (at < 0 || owner[at] != entity) {
                return null;
            }
            depth = at;
            owner[at] = null;
            cached.slot = slot[at];
            cached.verdict = verdict[at];
            cached.noAi = noAi[at];
            cached.mob = mob[at];
            cached.start = start[at];
            return cached;
        }
    }

    /** The fields of one closed host tick; reused so a measured row allocates nothing. */
    private static final class Frame {

        private int slot;
        private int verdict;
        private boolean noAi;
        private boolean mob;
        private long start;
    }

    private static final Frame cached = new Frame();
}
