/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * The whole-tick model of one villager row. It reproduces the state transitions a villager takes
 * while its AI is off and its state is pinned - the shared mob path plus the age bookkeeping of
 * AgeableMob, the unhappy counter of Villager#tick and the gossip decay clock - and it refuses
 * every row whose tick would leave that path.
 *
 * <p>The two steps whose input is the level clock or the row's own random stream run in the commit
 * segment on the tick thread: the ambient sound draw of Mob#baseTick and Villager#maybeDecayGossip.
 * Everything else is decided from the frozen row state alone.
 */
package io.izzel.arclight.common.prts.kernel.domain.entity.ownership.replica;

import io.izzel.arclight.common.prts.fixes.ownership.PrtsVillagerTickStateMixin;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.npc.Villager;

import java.util.concurrent.atomic.LongAdder;

/**
 * The whole-tick model of a villager whose AI is off. {@link #compute} reads and writes nothing but
 * the state it is given; every other entry point runs on the tick thread.
 */
public final class VillagerTick implements WholeTickModel {

    /** The one model instance of this class. */
    public static final WholeTickModel MODEL = new VillagerTick();

    private static final LongAdder[] REFUSAL_COUNTS = counters();
    private static final LongAdder COMPUTE_NANOS = new LongAdder();
    private static final LongAdder COMPUTE_ROWS = new LongAdder();
    private static final LongAdder APPLIED = new LongAdder();

    private VillagerTick() {
    }

    @Override
    public String name() {
        return "villager";
    }

    @Override
    public int refusal(Entity entity) {
        return MobTick.refusal((net.minecraft.world.entity.LivingEntity) entity);
    }

    @Override
    public void capture(Entity entity, TickState state) {
        MobTick.capture(entity, state);
        AbstractVillager villager = (AbstractVillager) entity;
        state.age = villager.getAge();
        state.unhappyCounter = villager.getUnhappyCounter();
        state.bbHeight = entity.getBbHeight();
    }

    @Override
    public boolean compute(TickState state) {
        if (!MobTick.compute(state)) {
            return false;
        }
        // AgeableMob#aiStep walks the age back to zero while the row is alive; crossing zero
        // refreshes the baby flag and runs the age boundary check, which is empty on a row with
        // no vehicle.
        if (state.age < 0) {
            state.age = state.age + 1;
        } else if (state.age > 0) {
            state.age = state.age - 1;
        }
        // Villager#tick tail: the unhappy counter runs down.
        if (state.unhappyCounter > 0) {
            state.unhappyCounter = state.unhappyCounter - 1;
        }
        return true;
    }

    @Override
    public void apply(Entity entity, TickState state) {
        MobTick.apply(entity, state);
        AbstractVillager villager = (AbstractVillager) entity;
        villager.setAge(state.age);
        villager.setUnhappyCounter(state.unhappyCounter);
    }

    @Override
    public void commitHostSteps(Entity entity, TickState state) {
        MobTick.commitHostSteps(entity);
        // Villager#maybeDecayGossip reads the level clock, so it runs here, on the tick thread,
        // once per tick exactly as the tick would have run it.
        PrtsVillagerTickStateMixin villager = (PrtsVillagerTickStateMixin) (Object) entity;
        long now = entity.level().getGameTime();
        if (villager.prts$lastGossipDecayTime() == 0L) {
            villager.prts$setLastGossipDecayTime(now);
        } else if (now >= villager.prts$lastGossipDecayTime() + 24000L) {
            villager.prts$gossips().decay();
            villager.prts$setLastGossipDecayTime(now);
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
