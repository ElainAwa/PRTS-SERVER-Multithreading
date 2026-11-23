/* SPDX-License-Identifier: GPL-3.0-or-later */
/**
 * The native entity read wave and seat write pump: the host freezes one row's read request, the
 * pooled workers search the frozen region, and the row's original body runs on the server thread in
 * seat order. Everything here is off unless the native program property names a mode.
 */
package io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec;
