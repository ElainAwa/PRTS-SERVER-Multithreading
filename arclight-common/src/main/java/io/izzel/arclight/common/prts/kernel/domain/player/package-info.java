/* SPDX-License-Identifier: GPL-3.0-or-later */
/** The player boundary contract: input, session and world state are separate owner resources, a
 * pickup deducts the source and adds the target under one atomic transaction, a command checks its
 * permission before any effect and never reports a ticket as success, and a reload publishes its
 * four versions at one safe point while a send completes only when the client acknowledged it. */
package io.izzel.arclight.common.prts.kernel.domain.player;
