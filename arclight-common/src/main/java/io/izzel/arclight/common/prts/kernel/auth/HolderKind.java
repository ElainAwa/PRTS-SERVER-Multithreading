/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.auth;

/** Where the thread behind a write attempt comes from. The distinction is the one that carries the
 * batch: a registered holder may own a domain, a kernel holder is the kernel itself, and an
 * unregistered holder has no world write right at all. */
public enum HolderKind {

    REGISTERED,
    UNREGISTERED,
    KERNEL
}
