/* SPDX-License-Identifier: GPL-3.0-or-later */
/**
 * The job system of the kernel: one tick's work is declared, frozen into a graph whose order is a
 * pure function of the declarations, and dispatched from that graph. The package owns the shape of a
 * job (its read and write domains, its true predecessors, its priority, its affinity, its
 * cancellation scope and its batch bound), the bounded intake a domain publishes into, the
 * affinity-aware scheduler and the point where the per-class metering of the job layer is taken.
 *
 * <p>It is its own package because the job system is the layer between the planning period and the
 * domains: the planning period freezes a graph, the domains declare what they want to run, and
 * neither of them has to know how the other is built.
 *
 * <p>The design section this package answers to is 2.15 (job graph, batch granularity); the scheduler
 * reads no wall clock, so the order it produces is the same for the same input.
 */
package io.izzel.arclight.common.prts.kernel.jobs;
