/* SPDX-License-Identifier: GPL-3.0-or-later */
/**
 * The write decision point: who may write which world domain, and what happens when it may not.
 *
 * <p>This package owns the owner token and its registry, the attempt and its three dispositions,
 * the accounting closure and the decision itself. It depends on the shared vocabulary and on the
 * intent channel only, reads no clock and takes no lock: an unregistered writer, or a write that
 * crosses a world, becomes an intent instead of touching world state.</p>
 */
package io.izzel.arclight.common.prts.kernel.auth;
