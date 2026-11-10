/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.sites;

/** A default-off pass-through gate for a write entry. Disabled, it admits every write; enabled, it
 * asks the injected permission before the write, so the caller keeps its original order/thread. */
public final class WriteTapGate {

    /** Context-aware permission; ENFORCE_NA contexts are not asked. */
    @FunctionalInterface
    public interface ContextPermission {
        boolean allows(WriteTapContext context);
    }

    /** Permission asked only while the gate is enabled. */
    @FunctionalInterface
    public interface Permission {
        boolean allows(String worldId, long owner, long expectedVersion);
    }

    private final String name;
    private final Permission permission;
    private volatile boolean enabled;

    public WriteTapGate(String name, Permission permission) {
        this.name = name;
        this.permission = permission == null ? (worldId, owner, version) -> false : permission;
    }

    public String name() { return name; }

    public boolean enabled() { return enabled; }

    /** Turns the gate on with the permission it asks; the default constructor state stays off. */
    public void enable() { enabled = true; }

    public void disable() { enabled = false; }

    /** Pass-through while disabled; the enabled path reports the permission result unchanged. */
    public boolean allows(String worldId, long owner, long expectedVersion) {
        return !enabled || permission.allows(worldId, owner, expectedVersion);
    }

    /** Disabled or ENFORCE_NA passes through; enabled asks the context permission. */
    public boolean allows(WriteTapContext context, ContextPermission contextPermission) {
        if (!enabled || context == null || context.enforceNa() || contextPermission == null) {
            return true;
        }
        return contextPermission.allows(context);
    }
}
