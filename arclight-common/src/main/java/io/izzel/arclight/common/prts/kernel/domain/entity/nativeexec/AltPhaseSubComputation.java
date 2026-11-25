/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec;

/** The three self-row sub-computations admitted by the design as read-wave candidates. Each one
 * freezes only its own row fields on the host, computes a pure intermediate value on a worker, and
 * is consumed once at its original write-back point. No world random source or cross-entity write
 * is involved, so pickup scheduling cannot perturb them. */
public final class AltPhaseSubComputation {

    private AltPhaseSubComputation() {
    }

    /** Frozen inputs and pure result of the AI-step head (E3). */
    public record AiHead(boolean effectiveAi, boolean immobile, int lerpSteps, double vx,
                         double vy, double vz) {
        /** Pure: a bounded normalized intent used by the later write-back, no host callback. */
        public double[] compute() {
            if (!effectiveAi || immobile || lerpSteps != 0) return new double[]{0.0D, 0.0D, 0.0D};
            double norm = Math.sqrt(vx * vx + vy * vy + vz * vz);
            if (!(norm > 0.0D)) return new double[]{0.0D, 0.0D, 0.0D};
            return new double[]{vx / norm, vy / norm, vz / norm};
        }
    }

    /** Frozen inputs and pure result of the server AI-step controls. */
    public record Controls(float rotation, float xxa, float zza, boolean jumping) {
        /** Pure: the clamped lateral/forward control pair, no world read. */
        public float[] compute() {
            float x = Math.max(-1.0F, Math.min(1.0F, xxa));
            float z = Math.max(-1.0F, Math.min(1.0F, zza));
            return new float[]{x, z, jumping ? 1.0F : 0.0F};
        }
    }

    /** Frozen inputs and pure result of the living entity tick tail. */
    public record TickTail(float yBodyRot, float run, float attackAnim, float swing) {
        /** Pure: the animation phase summary, no world read or event. */
        public float[] compute() {
            return new float[]{yBodyRot, run, Math.max(attackAnim, swing)};
        }
    }
}