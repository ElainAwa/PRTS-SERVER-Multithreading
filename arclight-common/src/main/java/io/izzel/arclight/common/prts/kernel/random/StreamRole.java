/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.random;

/**
 * The role a keyed stream plays for its object: the object's own stream or a stream forked off it.
 * The role is part of the key, so a fork can never collide with its parent. The wire name is
 * stable: it is what the key encoding hashes, so renaming a constant without keeping its code
 * would address a different stream.
 */
public enum StreamRole {

    /** The object's own stream; the default role of {@link StreamKey#of}. */
    PRIMARY("primary"),

    /** A stream forked from another stream; a fork consumes two values of its parent. */
    FORKED("forked");

    private final String code;

    StreamRole(String code) {
        this.code = code;
    }

    /** The stable wire name of this role. */
    public String code() {
        return code;
    }
}
