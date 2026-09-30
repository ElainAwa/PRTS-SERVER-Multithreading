/* SPDX-License-Identifier: GPL-3.0-or-later */
/**
 * The write decision point: who may write which world domain, and what happens when it may not.
 *
 * <p>This package owns the owner token and its registry, the attempt and its three dispositions,
 * the accounting closure and the trigger vocabulary of the decision. It reads no clock, takes no
 * lock and depends on the intent channel for the one disposition that needs it: an unregistered
 * writer, or a write that crosses a world, becomes an intent instead of touching world state.</p>
 */
package io.izzel.arclight.common.prts.kernel.auth;
