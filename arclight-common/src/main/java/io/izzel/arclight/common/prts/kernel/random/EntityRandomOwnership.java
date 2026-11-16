/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.random;

import net.minecraft.util.RandomSource;

import java.util.Objects;

/**
 * The contract for the random source an entity owns.
 *
 * <p>The source is created once with the entity's existing algorithm and seed and belongs to the
 * entity's current owner. An ownership transfer hands over the same source instance: the position,
 * the gaussian cache and any forks move with it and are never reset in process. The source is never
 * re-derived or reseeded per tick; the owner token is only recorded here, so the kernel's ownership
 * grant is what admits an owner to draw. Cross-restart bit-exact continuation is not claimed here,
 * because the upstream entity source does not expose that guarantee.
 */
public final class EntityRandomOwnership {

    private final RandomSource source;
    private long owner;
    private long handoffs;
    private long forks;

    private EntityRandomOwnership(long owner, RandomSource source) {
        this.owner = owner;
        this.source = source;
    }

    /**
     * Takes an existing entity source under the given owner. The source is adopted as it is: it is
     * not copied, re-derived or reseeded.
     *
     * @param owner the owner token that currently holds the entity
     * @param source the entity's own random source
     * @return the ownership contract for that source
     */
    public static EntityRandomOwnership adopt(long owner, RandomSource source) {
        return new EntityRandomOwnership(owner, Objects.requireNonNull(source, "source"));
    }

    /** The owned source; the same instance for the whole lifetime of the entity. */
    public RandomSource source() {
        return source;
    }

    /** The owner token that currently holds the source. */
    public long owner() {
        return owner;
    }

    /** The number of in-process ownership transfers performed on this source. */
    public long handoffs() {
        return handoffs;
    }

    /** The number of forks taken from the owned source. */
    public long forks() {
        return forks;
    }

    /**
     * Transfers ownership to another owner in process. The source instance, its position, its
     * gaussian cache and its forks are untouched; only the recorded owner changes.
     *
     * @param newOwner the owner token that takes over
     */
    public void handOff(long newOwner) {
        owner = newOwner;
        handoffs++;
    }

    /**
     * Forks the owned source. The child is derived from the current position, so ownership
     * transfers before or after the fork do not reset either stream.
     *
     * @return the forked source
     */
    public RandomSource fork() {
        forks++;
        return source.fork();
    }
}
