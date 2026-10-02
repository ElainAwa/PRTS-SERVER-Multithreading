/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.codes;

/** The three dispositions a write attempt can leave with. There is no fourth state: a skipped beat
 * and a degraded write are both refusals, so the accounting closure holds for every attempt. */
public enum WriteDisposition {

    GRANT,
    INTENT,
    DENY
}
