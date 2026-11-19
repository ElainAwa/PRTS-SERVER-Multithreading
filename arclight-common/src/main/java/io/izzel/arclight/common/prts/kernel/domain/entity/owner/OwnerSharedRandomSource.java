/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.owner;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;

/** The one shared world random stream, serialized: every draw takes the same lock the level would
 * take on its own thread, so two owner bodies can never enter the legacy source together while the
 * stream itself, its fork and its positional factories stay the level's upstream ones. */
public final class OwnerSharedRandomSource implements RandomSource {

    private final RandomSource base;
    private final Object lock = new Object();

    public OwnerSharedRandomSource(RandomSource base) {
        this.base = base;
    }

    @Override
    public RandomSource fork() {
        synchronized (lock) {
            return base.fork();
        }
    }

    @Override
    public PositionalRandomFactory forkPositional() {
        synchronized (lock) {
            return base.forkPositional();
        }
    }

    @Override
    public void setSeed(long seed) {
        synchronized (lock) {
            base.setSeed(seed);
        }
    }

    @Override
    public int nextInt() {
        synchronized (lock) {
            return base.nextInt();
        }
    }

    @Override
    public int nextInt(int bound) {
        synchronized (lock) {
            return base.nextInt(bound);
        }
    }

    @Override
    public long nextLong() {
        synchronized (lock) {
            return base.nextLong();
        }
    }

    @Override
    public boolean nextBoolean() {
        synchronized (lock) {
            return base.nextBoolean();
        }
    }

    @Override
    public float nextFloat() {
        synchronized (lock) {
            return base.nextFloat();
        }
    }

    @Override
    public double nextDouble() {
        synchronized (lock) {
            return base.nextDouble();
        }
    }

    @Override
    public double nextGaussian() {
        synchronized (lock) {
            return base.nextGaussian();
        }
    }
}
