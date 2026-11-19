/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.owner;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;

/** The level random source an owner worker sees: the row's own stream while that worker runs an
 * owner body, the level's original source on every other thread. A row stream is bound by
 * {@link OwnerRandomOwnership} for the duration of one body, so two worker bodies never draw from
 * the same legacy source. */
public final class OwnerThreadRandomSource implements RandomSource {

    private final RandomSource base;

    public OwnerThreadRandomSource(RandomSource base) {
        this.base = base;
    }

    private RandomSource active() {
        RandomSource row = OwnerRandomOwnership.bound();
        return row == null ? base : row;
    }

    @Override
    public RandomSource fork() {
        return active().fork();
    }

    @Override
    public PositionalRandomFactory forkPositional() {
        return active().forkPositional();
    }

    @Override
    public void setSeed(long seed) {
        active().setSeed(seed);
    }

    @Override
    public int nextInt() {
        return active().nextInt();
    }

    @Override
    public int nextInt(int bound) {
        return active().nextInt(bound);
    }

    @Override
    public long nextLong() {
        return active().nextLong();
    }

    @Override
    public boolean nextBoolean() {
        return active().nextBoolean();
    }

    @Override
    public float nextFloat() {
        return active().nextFloat();
    }

    @Override
    public double nextDouble() {
        return active().nextDouble();
    }

    @Override
    public double nextGaussian() {
        return active().nextGaussian();
    }
}