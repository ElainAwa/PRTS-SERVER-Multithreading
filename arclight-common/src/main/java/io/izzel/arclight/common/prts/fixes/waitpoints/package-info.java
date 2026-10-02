/* SPDX-License-Identifier: GPL-3.0-or-later */
/**
 * The world-side wait hooks: each class times one call site that may wait for a chunk it does not
 * have yet and reports the duration to the wait observation seam. No bound is read into a decision,
 * no wait is shortened, delayed or cancelled, and nothing here holds state.
 */
package io.izzel.arclight.common.prts.fixes.waitpoints;
