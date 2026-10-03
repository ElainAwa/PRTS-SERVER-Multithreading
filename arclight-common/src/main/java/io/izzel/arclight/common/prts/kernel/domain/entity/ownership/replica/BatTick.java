/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * The whole-tick model of one bat row. It reproduces the state transitions a bat takes while its
 * AI is off and its state is pinned - the shared mob path plus the tail of Bat#tick, which zeroes
 * the motion and snaps a resting bat onto the block below, or damps the vertical motion of a
 * flying one - and it refuses every row whose tick would leave that path.
 *
 * <p>A bat overrides pushEntities and checkFallDamage with empty bodies and is not pushable, so
 * its tick never queries the world for neighbours or for the block it stands on. The only steps
 * left to the commit segment are the ambient sound draw of Mob#baseTick, the goal control flags of
 * Mob#tick and Bat#setupAnimationStates, all of which run on the tick thread exactly once.
 */
package io.izzel.arclight.common.prts.kernel.domain.entity.ownership.replica;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ambient.Bat;

import java.util.concurrent.atomic.LongAdder;

/**
 * The whole-tick model of a bat whose AI is off. {@link #compute} reads and writes nothing but the
 * state it is given; every other entry point runs on the tick thread.
 */
public final class BatTick implements WholeTickModel {

    /** The one model instance of this class. */
    public static final WholeTickModel MODEL = new BatTick();

    private static final LongAdder[] REFUSAL_COUNTS = counters();
    private static final LongAdder COMPUTE_NANOS = new LongAdder();
    private static final LongAdder COMPUTE_ROWS = new LongAdder();
    private static final LongAdder APPLIED = new LongAdder();

    private BatTick() {
    }

    @Override
    public String name() {
        return "bat";
    }

    @Override
    public int refusal(Entity entity) {
        return MobTick.refusal((net.minecraft.world.entity.LivingEntity) entity);
    }

    @Override
    public void capture(Entity entity, TickState state) {
        MobTick.capture(entity, state);
        state.resting = ((Bat) entity).isResting();
        state.bbHeight = entity.getBbHeight();
    }

    @Override
    public boolean compute(TickState state) {
        if (!MobTick.compute(state)) {
            return false;
        }
        // Bat#tick tail: a resting bat stops moving and is snapped onto the block below, a flying
        // one keeps a damped vertical motion. The snap is a position write; the commit segment
        // makes the same call on the live row so the row's section bookkeeping stays intact.
        if (state.resting) {
            state.vx = 0.0;
            state.vy = 0.0;
            state.vz = 0.0;
            state.y = (double) Mth.floor(state.y) + 1.0 - (double) state.bbHeight;
            state.positionSnapped = true;
        } else {
            state.vy *= 0.6;
        }
        return true;
    }

    @Override
    public void apply(Entity entity, TickState state) {
        MobTick.apply(entity, state);
        if (state.positionSnapped) {
            entity.setPosRaw(state.x, state.y, state.z);
        }
    }

    @Override
    public void commitHostSteps(Entity entity, TickState state) {
        MobTick.commitHostSteps(entity);
        // Bat#setupAnimationStates steps the two animation states from the resting flag and the
        // tick counter; both live on the row and are stepped here on the tick thread.
        Bat bat = (Bat) entity;
        if (bat.isResting()) {
            bat.flyAnimationState.stop();
            bat.restAnimationState.startIfStopped(bat.tickCount);
        } else {
            bat.restAnimationState.stop();
            bat.flyAnimationState.startIfStopped(bat.tickCount);
        }
    }

    @Override
    public void noteCompute(long nanos) {
        COMPUTE_NANOS.add(nanos);
        COMPUTE_ROWS.increment();
    }

    @Override
    public long computeNanos() {
        return COMPUTE_NANOS.sum();
    }

    @Override
    public long computeRows() {
        return COMPUTE_ROWS.sum();
    }

    @Override
    public void noteApplied() {
        APPLIED.increment();
    }

    @Override
    public long appliedCount() {
        return APPLIED.sum();
    }

    @Override
    public void noteRefusal(int reason) {
        if (reason > 0 && reason < REFUSAL_COUNTS.length) {
            REFUSAL_COUNTS[reason].increment();
        }
    }

    @Override
    public LongAdder[] refusalCounts() {
        return REFUSAL_COUNTS;
    }

    @Override
    public String[] refusalNames() {
        return MobTick.refusalNames();
    }

    @Override
    public void reset() {
        for (LongAdder counter : REFUSAL_COUNTS) {
            counter.reset();
        }
        COMPUTE_NANOS.reset();
        COMPUTE_ROWS.reset();
        APPLIED.reset();
    }

    private static LongAdder[] counters() {
        LongAdder[] counters = new LongAdder[MobTick.REFUSALS];
        for (int index = 0; index < counters.length; index++) {
            counters[index] = new LongAdder();
        }
        return counters;
    }
}
