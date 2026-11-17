/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.support;

import java.util.Objects;
import java.util.Set;

/** The declared support of one entity class: its class name and mod origin, the support kind, the
 * traits it carries and the one program kind the per-class table dispatches to. Only a supported
 * or special-subset class counts as supported; an unknown, modded or third-party class is an
 * explicit C1 fallback and is never counted as support. */
public record EntitySupportProfile(String className, String modId, SupportKind kind,
                                   Set<EntityTrait> traits, String program) {

    /** How far one class is supported; the special subset is a subset of the supported set. */
    public enum SupportKind {
        SUPPORTED,
        SPECIAL_SUBSET,
        C1_FALLBACK
    }

    public EntitySupportProfile {
        Objects.requireNonNull(className, "className");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(traits, "traits");
        Objects.requireNonNull(program, "program");
        if (className.isEmpty()) {
            throw new IllegalArgumentException("a support profile needs its class");
        }
        if (program.isEmpty()) {
            throw new IllegalArgumentException("a support profile needs its program name");
        }
        traits = Set.copyOf(traits);
        modId = modId == null ? "minecraft" : modId;
        if (kind != SupportKind.C1_FALLBACK && traits.isEmpty()) {
            throw new IllegalArgumentException("a supported class declares at least one trait");
        }
    }

    /** Whether this class counts as supported (the special subset is still a subset). */
    public boolean supported() {
        return kind == SupportKind.SUPPORTED || kind == SupportKind.SPECIAL_SUBSET;
    }

    /** Whether the profile carries one trait. */
    public boolean has(EntityTrait trait) {
        return traits.contains(trait);
    }
}
