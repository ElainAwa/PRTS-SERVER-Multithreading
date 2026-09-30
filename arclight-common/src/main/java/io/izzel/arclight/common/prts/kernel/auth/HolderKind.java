/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.auth;

/**
 * Where the thread behind a write attempt comes from.
 *
 * <p>The distinction is the one that carries the batch: a registered holder may own a domain, a
 * kernel holder is the kernel itself, and an unregistered holder has no world write right at all.
 * Its reads pass, its writes go to the intent channel, and the granted count for unregistered
 * writes stays at zero.</p>
 */
public enum HolderKind {

    /** A thread class or thread set that passed admission and was registered. */
    REGISTERED,
    /** A thread that was never registered; no world write right, reads pass. */
    UNREGISTERED,
    /** The kernel itself. */
    KERNEL
}
