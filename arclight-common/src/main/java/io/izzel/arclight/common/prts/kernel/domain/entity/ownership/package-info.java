/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * The ownership hook fixture of the entity domain: it claims the rows of one entity tick before
 * the host runs them, decides at the host entry whether a row keeps its original tick or is
 * skipped under a settled ownership token, and hands every row it cannot own back to the host
 * exactly once. The whole package is off unless the process declares it, and it writes no world
 * state: it exists to measure the lifecycle and the counters, not to take the tick over.
 */
package io.izzel.arclight.common.prts.kernel.domain.entity.ownership;
