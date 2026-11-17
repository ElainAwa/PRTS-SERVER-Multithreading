/* SPDX-License-Identifier: GPL-3.0-or-later */
/** The kernel module of one process: the owner context, the planning and dispatch passes, the
 * commit and effect books, the ledger of write and wait decisions, and the readings that report
 * them. The module owns the shared runtime and hands each domain its slice of a tick. */
package io.izzel.arclight.common.prts.kernel;
