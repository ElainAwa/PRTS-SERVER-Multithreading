/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.decision;

import io.izzel.arclight.common.prts.kernel.observe.BukkitEventOrderObserver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Whole-event admission: an unnamed or mixed chain keeps one legacy path, never a worker half. */
public final class EventAdmission {

    /** The execution mode of one event chain. */
    public enum Mode {
        /** Every listener of the chain may run in the owner context under its declared contract. */
        OWNER_SYNC,
        /** The whole chain keeps the host dispatch path. */
        LEGACY,
        /** Declared, but not as owner-synced: treated as any other legacy listener. */
        UNKNOWN
    }

    /** What one whole event chain was admitted as, and why. */
    public record Verdict(Mode mode, String reason, int listeners, int unknownListeners) {

        public Verdict {
            Objects.requireNonNull(mode, "mode");
            reason = reason == null ? "" : reason;
        }

        public boolean ownerSync() {
            return mode == Mode.OWNER_SYNC;
        }

        public boolean legacy() {
            return mode == Mode.LEGACY;
        }
    }

    private final Map<String, Mode> declared = new HashMap<>();
    private final Mode defaultMode;

    private EventAdmission(Mode defaultMode) {
        this.defaultMode = defaultMode;
    }

    /** The default table: no listener is declared, so every chain is legacy as a whole. */
    public static EventAdmission passthrough() {
        return new EventAdmission(Mode.LEGACY);
    }

    /** The owning table: an unnamed listener still refuses the whole chain to LEGACY. */
    public static EventAdmission ownerSync() {
        return new EventAdmission(Mode.OWNER_SYNC);
    }

    /** Declares the contract one listener class (or interface) is admitted under. */
    public EventAdmission declare(Class<?> listenerType, Mode mode) {
        Objects.requireNonNull(listenerType, "listenerType");
        return declareName(listenerType.getName(), mode);
    }

    /** Declares one listener by binary name; an unmatched name simply never matches a listener. */
    public EventAdmission declareName(String listenerName, Mode mode) {
        Objects.requireNonNull(listenerName, "listenerName");
        Objects.requireNonNull(mode, "mode");
        if (listenerName.isBlank()) {
            throw new IllegalArgumentException("a listener name must not be blank");
        }
        declared.put(listenerName, mode);
        return this;
    }

    /** The declared mode, matching the exact class first, then superclasses and interfaces. */
    private Mode declaredMode(Class<?> listenerType) {
        Mode exact = declared.get(listenerType.getName());
        if (exact != null) {
            return exact;
        }
        for (Class<?> type = listenerType.getSuperclass(); type != null; type = type.getSuperclass()) {
            Mode inherited = declared.get(type.getName());
            if (inherited != null) {
                return inherited;
            }
        }
        return declaredInterfaceMode(listenerType);
    }

    private Mode declaredInterfaceMode(Class<?> listenerType) {
        for (Class<?> type = listenerType; type != null; type = type.getSuperclass()) {
            for (Class<?> iface : type.getInterfaces()) {
                Mode inherited = declared.get(iface.getName());
                if (inherited != null) {
                    return inherited;
                }
                Mode nested = declaredInterfaceMode(iface);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    /** Admits one whole chain before listeners run; only an all-OWNER_SYNC chain is owner-synced. */
    public Verdict admit(List<Class<?>> listenerTypes) {
        return admit(listenerTypes, false);
    }

    /** Inherited form: an interface/superclass declaration matches its concrete listener. */
    Verdict admitInherited(List<Class<?>> listenerTypes) {
        return admit(listenerTypes, true);
    }

    private Verdict admit(List<Class<?>> listenerTypes, boolean inherited) {
        if (listenerTypes == null || listenerTypes.isEmpty()) {
            return new Verdict(defaultMode, "empty-chain", 0, 0);
        }
        int unknown = 0;
        int ownerSync = 0;
        int legacy = 0;
        for (Class<?> listenerType : listenerTypes) {
            Mode declaredMode = listenerType == null ? null
                : inherited ? declaredMode(listenerType) : declared.get(listenerType.getName());
            // A listener the table does not name is unknown when the table claims owner-sync: an
            // unnamed listener is never assumed safe. A legacy table keeps it on the host path.
            Mode mode = declaredMode != null ? declaredMode
                : defaultMode == Mode.OWNER_SYNC ? Mode.UNKNOWN : defaultMode;
            if (mode == Mode.UNKNOWN) {
                unknown++;
            } else if (mode == Mode.OWNER_SYNC) {
                ownerSync++;
            } else {
                legacy++;
            }
        }
        int listeners = listenerTypes.size();
        if (unknown > 0) {
            return new Verdict(Mode.LEGACY, "unknown-listener", listeners, unknown);
        }
        if (legacy > 0 && ownerSync > 0) {
            // A mixed chain is one conservative unit: the node must not run a worker half and a
            // main half of the same event.
            return new Verdict(Mode.LEGACY, "mixed-chain", listeners, 0);
        }
        if (legacy > 0) {
            return new Verdict(Mode.LEGACY, "declared-legacy", listeners, 0);
        }
        return new Verdict(Mode.OWNER_SYNC, "declared-owner-sync", listeners, 0);
    }

    /** The admission of an empty chain: the table's own default, no listener named. */
    public Verdict admitEmpty() {
        return new Verdict(defaultMode, "empty-chain", 0, 0);
    }

    /** The admission of one chain as a list: the caller keeps the order the real dispatch uses. */
    public Verdict admitInOrder(Class<?>... listenerTypes) {
        List<Class<?>> listeners = new ArrayList<>();
        if (listenerTypes != null) {
            for (Class<?> listenerType : listenerTypes) {
                listeners.add(listenerType);
            }
        }
        return admit(listeners);
    }

    /**
     * The read-only observation of the host event order for the caller's evidence. It is never an
     * admission input: a missing reading admits nothing and changes no verdict.
     */
    public Optional<BukkitEventOrderObserver.Reading> eventOrderWitness() {
        return BukkitEventOrderObserver.reading();
    }
}
