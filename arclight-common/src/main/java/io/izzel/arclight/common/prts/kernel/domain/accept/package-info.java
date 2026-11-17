/* SPDX-License-Identifier: GPL-3.0-or-later */
/** The acceptance contract: one business identity joins its business, execution/effect and resource
 * ledgers, a green state without its effect or with a leaked resource is a fake green, a waiter that
 * holds what its holder needs is a resource wait cycle refused before admission, and every deadline
 * starts at the first arrival and is inherited without reset across batching, retries and ticks. */
package io.izzel.arclight.common.prts.kernel.domain.accept;
