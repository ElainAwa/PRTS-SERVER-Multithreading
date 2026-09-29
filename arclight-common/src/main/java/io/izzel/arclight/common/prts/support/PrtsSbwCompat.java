/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.support;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;

/**
 * What this server knows about an optional vehicle mod, expressed as strings and reflection.
 *
 * <p>The mod is optional, so nothing here may become a compile time or class loading dependency of
 * the server. Every name is a string, and every lookup is made on first use only. A lookup that
 * fails reports "unknown", and each caller then keeps the behaviour of the mod instead of guessing:
 * an unreadable setting must never change how a packet is sent.</p>
 *
 * <p>Two payload kinds are recognised by the name of their class. Both are final classes, so a
 * name comparison is exact for them and does not load anything.</p>
 */
public final class PrtsSbwCompat {

    /** Mod id the members of this class are written for; the mixins are gated on it. */
    public static final String MOD_ID = "superbwarfare";

    /** Tick interval the fast projectile motion sync is spread over. */
    public static final int MOTION_SYNC_INTERVAL = 3;

    /** Tick interval the friend-or-foe update is spread over. */
    public static final int IFF_THROTTLE_TICKS = 3;

    /** Squared distance a particle is still sent within. */
    public static final double PARTICLE_RADIUS_SQ = 96.0 * 96.0;

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

    /** Default the mod ships for the interval setting; only that value is improved here. */
    private static final int SYNC_ENTITY_INTERVAL_DEFAULT = 1;

    private static volatile boolean intervalResolved;
    private static volatile Object intervalValue;
    private static volatile Method intervalGetter;
    private static volatile boolean vehicleGetterResolved;
    private static volatile Method vehicleGetter;

    private PrtsSbwCompat() {
    }

    /**
     * Reports whether the payload is one of the friend-or-foe updates.
     *
     * @param payload payload being sent
     * @return true when the payload is one of the two relation updates
     */
    public static boolean isIffPayload(Object payload) {
        String name = payload.getClass().getName();
        return CLASS_ENTITY_RELATION_SYNC.equals(name) || CLASS_PLAYER_INFO_SYNC.equals(name);
    }

    /**
     * Reports whether the payload is the vehicle shoot broadcast.
     *
     * @param payload payload being sent
     * @return true when the payload is the vehicle shoot message
     */
    public static boolean isVehicleShootPayload(Object payload) {
        return CLASS_VEHICLE_SHOOT.equals(payload.getClass().getName());
    }

    /**
     * Reports whether the friend-or-foe update may be spread over several ticks.
     *
     * <p>The setting keeps the operator in charge: as long as it still holds the value the mod
     * ships, updates go out every {@link #IFF_THROTTLE_TICKS} ticks instead of every tick. A value
     * the operator changed is respected without a second filter, and a value that cannot be read
     * counts as changed.</p>
     *
     * @param anchor payload whose class loader can reach the mod, used only for the first lookup
     * @return true when the update should be spread over ticks
     */
    public static boolean iffThrottleApplies(Object anchor) {
        return configuredInterval(anchor) == SYNC_ENTITY_INTERVAL_DEFAULT;
    }

    /**
     * Reads the configured sync interval of the mod.
     *
     * @param anchor payload whose class loader can reach the mod
     * @return the configured value, or {@code -1} when it cannot be read
     */
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

    /**
     * Reads the vehicle identifier a shoot broadcast was built for.
     *
     * @param payload payload being sent
     * @return the identifier, or {@code null} when it cannot be read
     */
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

    /**
     * Loads a mod class through the loader of a class that certainly comes from the same mod.
     *
     * @param name class to load
     * @param anchor instance of a class of the same mod
     * @return the class, or {@code null} when it cannot be loaded
     */
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
