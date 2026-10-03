/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

/** The sink a domain writes its observation fields into. The kernel owns the formatting and the
 * order of the readout, so a domain contributes names and values only. */
public interface DomainReadings {

    void add(String name, double value);

    void add(String name, long value);
}
