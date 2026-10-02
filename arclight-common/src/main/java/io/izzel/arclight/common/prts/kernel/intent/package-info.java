/* SPDX-License-Identifier: GPL-3.0-or-later */
/**
 * The controlled channel a write takes when it may not write directly, and the segment that walks
 * it. Owns the whole deferral - channel, depth, frozen order and the one spot where the write
 * finally lands - so no caller can write out of order or out of turn. Design reference: the
 * write-right section.
 */
package io.izzel.arclight.common.prts.kernel.intent;
