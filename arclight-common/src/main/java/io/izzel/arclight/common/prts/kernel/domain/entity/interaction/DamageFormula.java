/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.interaction;

import java.util.Objects;

/**
 * The declared damage calculation of one attack, in the same order the host applies it: blocking,
 * freezing, the helmet reduction, the invulnerability-cooldown refusal, armor and toughness,
 * resistance, enchantment protection and absorption, and finally the event's cancel or modified
 * amount. The calculation is a pure function of the frozen input; it is the parity reference for
 * the interaction contract and writes nothing.
 */
public final class DamageFormula {

    /** What the damage event decided; a cancelled event applies no damage. */
    public enum Verdict {
        APPLIED,
        CANCELLED
    }

    /** The frozen input of one damage calculation. */
    public record Input(float original, boolean blocked, boolean freezingExtra,
                        boolean helmetDamage, boolean helmetPresent, boolean cooldownActive,
                        float lastHurt, float armorValue, float armorToughness, boolean bypassArmor,
                        boolean bypassEffects, boolean bypassResistance, int resistanceAmplifier,
                        float magicProtection, float absorption) {

        public Input {
            if (original < 0.0F) {
                throw new IllegalArgumentException("the original damage cannot be negative");
            }
        }
    }

    /** What one calculation produced; the final damage is the value the health write would use. */
    public record Result(float blockingModifier, float freezingModifier, float helmetModifier,
                         float armorModifier, float resistanceModifier, float magicModifier,
                         float absorptionModifier, float finalDamage, Verdict verdict) {

        public boolean cancelled() {
            return verdict == Verdict.CANCELLED;
        }
    }

    private DamageFormula() {
    }

    /** Applies the host formula in order and returns the modifiers and the final damage. */
    public static Result apply(Input input, float eventDamage) {
        return apply(input, eventDamage, false);
    }

    /** Applies the host formula; a cancelled event stops at the cancel verdict. */
    public static Result apply(Input input, float eventDamage, boolean eventCancelled) {
        Objects.requireNonNull(input, "input");
        float damage = input.original();
        float blocking = input.blocked() ? -damage : -0.0F;
        damage += blocking;
        float freezing = input.freezingExtra() ? -(damage - damage * 5.0F) : -0.0F;
        damage += freezing;
        float helmet = input.helmetDamage() && input.helmetPresent()
            ? -(damage - damage * 0.75F) : -0.0F;
        damage += helmet;
        if (input.cooldownActive() && damage <= input.lastHurt()) {
            return new Result(blocking, freezing, helmet, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F,
                Verdict.CANCELLED);
        }
        float armor = input.bypassArmor() ? -0.0F
            : -(damage - damageAfterAbsorb(damage, input.armorValue(), input.armorToughness()));
        damage += armor;
        float resistance = resistanceModifier(input, damage);
        damage += resistance;
        float magic = input.magicProtection() > 0.0F
            ? -(damage - damageAfterMagicAbsorb(damage, input.magicProtection())) : -0.0F;
        damage += magic;
        float absorbed = Math.max(Math.min(damage, input.absorption()), 0.0F);
        float absorptionModifier = -absorbed;
        float afterAbsorption = damage - absorbed;
        if (eventCancelled) {
            return new Result(blocking, freezing, helmet, armor, resistance, magic,
                absorptionModifier, 0.0F, Verdict.CANCELLED);
        }
        float finalDamage = eventDamage > 0.0F ? eventDamage : afterAbsorption;
        return new Result(blocking, freezing, helmet, armor, resistance, magic,
            absorptionModifier, finalDamage, Verdict.APPLIED);
    }

    /** The vanilla armor reduction of one damage value. */
    public static float damageAfterAbsorb(float damage, float armorValue, float armorToughness) {
        float toughnessFactor = 2.0F + armorToughness / 4.0F;
        float clamped = Math.max(Math.min(armorValue - damage / toughnessFactor, 20.0F),
            armorValue * 0.2F);
        return damage * (1.0F - clamped / 25.0F);
    }

    /** The vanilla enchantment-protection reduction of one damage value. */
    public static float damageAfterMagicAbsorb(float damage, float protection) {
        float clamped = Math.max(Math.min(protection, 20.0F), 0.0F);
        return damage * (1.0F - clamped / 25.0F);
    }

    private static float resistanceModifier(Input input, float damage) {
        if (input.bypassEffects() || input.bypassResistance()
            || input.resistanceAmplifier() < 0) {
            return -0.0F;
        }
        int level = (input.resistanceAmplifier() + 1) * 5;
        int kept = 25 - level;
        return -(damage - damage * kept / 25.0F);
    }
}
