/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.dispatch;

import io.izzel.arclight.common.prts.kernel.diff.StateHasher;

/**
 * Compares the kinematics of two rows bit by bit.
 *
 * <p>The write-back leg may be narrowed to what the host itself already holds. That decision is a
 * comparison of bit patterns, not of numeric equality: the values travel from the frozen view to
 * the entity through the same accessors their own tick used, so two values that mean the same
 * number but differ in one bit are two different states, and only the exact one may be taken over.
 * Two values count as the same only when their raw bits are the same, which is also why negative
 * zero and positive zero are not the same here.</p>
 *
 * <p>Orientation is compared as the float the entity stores, because that is what the write would
 * set: a yaw the view normalized into a double keeps its value only after the narrowing the setter
 * performs.</p>
 */
public final class KinematicIdentity {

    private KinematicIdentity() {
    }

    /**
     * Answers whether two rows carry the same kinematics.
     *
     * @param expected the row the domain computed
     * @param actual   the row the world holds
     * @return whether every position, orientation and velocity value has the same raw bits
     */
    public static boolean matches(StateHasher.Slice expected, StateHasher.Slice actual) {
        return sameDouble(expected.x(), actual.x())
            && sameDouble(expected.y(), actual.y())
            && sameDouble(expected.z(), actual.z())
            && sameFloat((float) expected.yaw(), (float) actual.yaw())
            && sameFloat((float) expected.pitch(), (float) actual.pitch())
            && sameDouble(expected.velX(), actual.velX())
            && sameDouble(expected.velY(), actual.velY())
            && sameDouble(expected.velZ(), actual.velZ());
    }

    /**
     * Answers whether two doubles have the same raw bits.
     *
     * @param left  one value
     * @param right the other value
     * @return whether the two bit patterns are equal
     */
    public static boolean sameDouble(double left, double right) {
        return Double.doubleToRawLongBits(left) == Double.doubleToRawLongBits(right);
    }

    /**
     * Answers whether two floats have the same raw bits.
     *
     * @param left  one value
     * @param right the other value
     * @return whether the two bit patterns are equal
     */
    public static boolean sameFloat(float left, float right) {
        return Float.floatToRawIntBits(left) == Float.floatToRawIntBits(right);
    }
}
