/* SPDX-License-Identifier: GPL-3.0-or-later */
/** The entity update program shape: the declared phases, read/write set, capabilities, allowed
 * effects, cancel points and successor steps of one complete entity update, with the phase witness
 * and the read-only host parity reference. Every production path that builds a program is dormant
 * and off by default: the compute-only body and its frozen read sources only run when the internal
 * switch is set, and no program writes in this build. */
package io.izzel.arclight.common.prts.kernel.domain.entity.program;
