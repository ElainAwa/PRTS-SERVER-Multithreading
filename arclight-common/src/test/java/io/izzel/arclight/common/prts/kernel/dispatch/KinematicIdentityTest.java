/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.dispatch;

import io.izzel.arclight.common.prts.config.PrtsConfigManager;
import io.izzel.arclight.common.prts.kernel.config.KernelSettings;
import io.izzel.arclight.common.prts.kernel.diff.StateHasher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The takeover boundary of the write-back leg: only a row whose kinematics the host itself already
 * holds, bit for bit, may be taken over, and the switch that narrows the leg to that set is off by
 * default so an operator never gets the narrow mode by accident.
 */
class KinematicIdentityTest {

    @Test
    void theTakeoverBoundaryIsOffInTheDeclaredDefaults() {
        assertEquals(Boolean.FALSE, PrtsConfigManager.entries().get(PrtsConfigManager.KERNEL)
            .features().get(KernelSettings.DISPATCH_IDENTICAL_ONLY));
        assertFalse(KernelSettings.dispatchIdenticalOnly());
    }

    @Test
    void twoRowsWithTheSameKinematicsAgree() {
        assertTrue(matches(row(), row()));
    }

    @Test
    void aOneBitDifferenceInAnyPositionOrTheOrientationSeparatesTwoRows() {
        assertFalse(matches(row(), row().withX(Math.nextUp(row().x()))), "position x");
        assertFalse(matches(row(), row().withY(Math.nextUp(row().y()))), "position y");
        assertFalse(matches(row(), row().withZ(Math.nextUp(row().z()))), "position z");
        assertFalse(matches(row(), row().withYaw(Math.nextUp((float) row().yaw()))), "yaw");
        assertFalse(matches(row(), row().withPitch(Math.nextUp((float) row().pitch()))), "pitch");
    }

    @Test
    void everyVelocityComponentIsCompared() {
        assertFalse(matches(row(), row().withVelocity(0.1, 0.0, 0.0)), "velocity x");
        assertFalse(matches(row(), row().withVelocity(0.0, 0.2, 0.0)), "velocity y");
        assertFalse(matches(row(), row().withVelocity(0.0, 0.0, 0.3)), "velocity z");
    }

    @Test
    void orientationIsComparedAsTheFloatTheEntityStores() {
        assertTrue(matches(row(), row().withYaw(30.0 + 1.0E-9)),
            "a double the setter narrows to the same float is the same orientation");
        assertTrue(matches(row(), row().withYaw(30.0F)),
            "a float widened to a double is the same orientation");
        assertFalse(matches(row(), row().withYaw(Math.nextUp(30.0F))),
            "the next float up is a different orientation");
    }

    @Test
    void negativeZeroAndZeroAreDifferentStates() {
        assertFalse(matches(row(), row().withX(-0.0)));
        assertFalse(KinematicIdentity.sameDouble(0.0, -0.0));
        assertTrue(KinematicIdentity.sameDouble(-0.0, -0.0));
    }

    @Test
    void twoNotANumbersWithTheSameBitsAreTheSameState() {
        assertTrue(matches(row().withX(Double.NaN), row().withX(Double.NaN)));
    }

    private static boolean matches(Row left, Row right) {
        return KinematicIdentity.matches(left.slice(), right.slice());
    }

    private static Row row() {
        return new Row(1.0, 2.0, 3.0, 30.0, 15.0, 0.0, 0.0, 0.0);
    }

    /** One row a test can copy while naming the single field it changes. */
    private record Row(double x, double y, double z, double yaw, double pitch, double velX,
                       double velY, double velZ) {

        StateHasher.Slice slice() {
            return new StateHasher.Slice("minecraft:overworld", "r0.0", 1L, 5L, x, y, z, yaw, pitch,
                velX, velY, velZ, 0L, 0L, 0L);
        }

        Row withX(double value) {
            return new Row(value, y, z, yaw, pitch, velX, velY, velZ);
        }

        Row withY(double value) {
            return new Row(x, value, z, yaw, pitch, velX, velY, velZ);
        }

        Row withZ(double value) {
            return new Row(x, y, value, yaw, pitch, velX, velY, velZ);
        }

        Row withYaw(double value) {
            return new Row(x, y, z, value, pitch, velX, velY, velZ);
        }

        Row withPitch(double value) {
            return new Row(x, y, z, yaw, value, velX, velY, velZ);
        }

        Row withVelocity(double vx, double vy, double vz) {
            return new Row(x, y, z, yaw, pitch, vx, vy, vz);
        }
    }
}
