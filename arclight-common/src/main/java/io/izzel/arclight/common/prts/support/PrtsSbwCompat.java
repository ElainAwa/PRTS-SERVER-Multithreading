/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.support;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;

/**
 * What this server knows about the optional vehicle mod, as strings and reflection: nothing here may
 * become a compile time or class loading dependency, and a lookup that fails reports "unknown" so
 * the caller keeps the mod's own behaviour instead of guessing.
 */
public final class PrtsSbwCompat {

    public static final String MOD_ID = "superbwarfare";

    public static final int MOTION_SYNC_INTERVAL = 3;

    public static final int IFF_THROTTLE_TICKS = 3;

    public static final double PARTICLE_RADIUS_SQ = 96.0 * 96.0;

    public static final int PROJECTILE_LIFE_DEFAULT = 400;

    public static final int PROJECTILE_LIFE_TICKS = 60;

    private static final String CLASS_ENTITY_RELATION_SYNC =
        "com.atsuishio.superbwarfare.network.message.receive.EntityRelationSyncMessage";
    private static final String CLASS_PLAYER_INFO_SYNC =
        "com.atsuishio.superbwarfare.network.message.receive.PlayerInfoSyncMessage";
    private static final String CLASS_VEHICLE_SHOOT =
        "com.atsuishio.superbwarfare.network.message.receive.VehicleShootClientMessage";
    private static final String CLASS_SYNC_CONFIG =
        "com.atsuishio.superbwarfare.config.server.SyncConfig";
    private static final String FIELD_SYNC_ENTITY_INTERVAL = "SYNC_ENTITY_INTERVAL";
    private static final String METHOD_GET_VEHICLE = "getVehicle";

    private static final int SYNC_ENTITY_INTERVAL_DEFAULT = 1;

    private static volatile boolean intervalResolved;
    private static volatile Object intervalValue;
    private static volatile Method intervalGetter;
    private static volatile boolean vehicleGetterResolved;
    private static volatile Method vehicleGetter;

    private PrtsSbwCompat() {
    }

    /** @return true when the payload is one of the two friend-or-foe relation updates */
    public static boolean isIffPayload(Object payload) {
        String name = payload.getClass().getName();
        return CLASS_ENTITY_RELATION_SYNC.equals(name) || CLASS_PLAYER_INFO_SYNC.equals(name);
    }

    /** @return true when the payload is the vehicle shoot broadcast */
    public static boolean isVehicleShootPayload(Object payload) {
        return CLASS_VEHICLE_SHOOT.equals(payload.getClass().getName());
    }

    /**
     * Reports whether the friend-or-foe update may be spread over ticks: only while the setting still
     * holds the value the mod ships. A value the operator changed is respected, and a value that
     * cannot be read counts as changed.
     */
    public static boolean iffThrottleApplies(Object anchor) {
        return configuredInterval(anchor) == SYNC_ENTITY_INTERVAL_DEFAULT;
    }

    private static int configuredInterval(Object anchor) {
        try {
            if (!intervalResolved) {
                resolveInterval(anchor);
            }
            Object value = intervalValue;
            Method getter = intervalGetter;
            if (value == null || getter == null) {
                return -1;
            }
            Object raw = getter.invoke(value);
            return raw instanceof Number number ? number.intValue() : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static synchronized void resolveInterval(Object anchor) {
        if (intervalResolved) {
            return;
        }
        try {
            Class<?> config = load(CLASS_SYNC_CONFIG, anchor);
            if (config != null) {
                Field field = config.getField(FIELD_SYNC_ENTITY_INTERVAL);
                Object value = field.get(null);
                intervalValue = value;
                intervalGetter = value.getClass().getMethod("get");
            }
        } catch (Throwable ignored) {
            intervalValue = null;
            intervalGetter = null;
        }
        // A lookup that failed is not repeated: the answer is "unknown" for this process.
        intervalResolved = true;
    }

    /** @return the vehicle identifier of a shoot broadcast, or null when it cannot be read */
    public static UUID vehicleId(Object payload) {
        try {
            if (!vehicleGetterResolved) {
                resolveVehicleGetter(payload);
            }
            Method getter = vehicleGetter;
            if (getter == null) {
                return null;
            }
            Object raw = getter.invoke(payload);
            return raw instanceof UUID uuid ? uuid : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static synchronized void resolveVehicleGetter(Object payload) {
        if (vehicleGetterResolved) {
            return;
        }
        try {
            vehicleGetter = payload.getClass().getMethod(METHOD_GET_VEHICLE);
        } catch (Throwable ignored) {
            vehicleGetter = null;
        }
        vehicleGetterResolved = true;
    }

    private static Class<?> load(String name, Object anchor) {
        try {
            return Class.forName(name, false, anchor.getClass().getClassLoader());
        } catch (Throwable ignored) {
            try {
                return Class.forName(name, false, PrtsSbwCompat.class.getClassLoader());
            } catch (Throwable ignoredAgain) {
                return null;
            }
        }
    }
}
