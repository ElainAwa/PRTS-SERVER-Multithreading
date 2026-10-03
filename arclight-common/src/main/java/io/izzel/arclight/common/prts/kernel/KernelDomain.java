/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import java.util.List;

/** One domain the kernel drives. The kernel owns the tick and the readout; a domain owns the work of
 * its own rows and hands its readings and its self checks back through this handle, so the kernel
 * names no domain implementation and a domain can be added or replaced on its own. */
public interface KernelDomain {

    /** The name this domain is known by in the readout. */
    String id();

    /** Advances this domain by one tick of the kernel. */
    void tick(long tickIndex);

    /** Closes everything this domain still holds. */
    void shutdown();

    /** Clears the live counters of this domain. */
    void reset();

    /** Contributes the observation fields of this domain. */
    void readings(DomainReadings readings);

    /** Runs the self checks of this domain; returns their lines and records their failures. */
    List<String> selfCheck(List<String> failures, long tick);
}
