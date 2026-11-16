/* SPDX-License-Identifier: GPL-3.0-or-later */
/** The committed publication contract: one object publishes its committed state under a monotonic
 * revision, a session generation invalidates stale readers, and the tracker send path reads the
 * last published state instead of the live row. The package is contract-only: no production path
 * publishes or sends, the tracker, encoding and network stay with the host, and no owner network
 * pool exists. */
package io.izzel.arclight.common.prts.kernel.domain.entity.publish;
