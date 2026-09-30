/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.shares;

/**
 * The result of recomputing the budget conservation of one table.
 *
 * <p>The check is an equation, not a memory: the sum of every class share, the reserved column and
 * the fixed host overhead has to fit into the budget of the tick. It is recomputed from the table
 * rather than remembered from planning time, so a row edited after the fact cannot report a budget
 * that never held.</p>
 *
 * @param ok        whether the budget holds
 * @param item      what is over when it does not, or an empty string
 * @param overByMs  by how much it is over, in milliseconds
 */
public record ConservationCheck(boolean ok, String item, double overByMs) {

    /** @return a conservation that holds */
    public static ConservationCheck holds() {
        return new ConservationCheck(true, "", 0.0);
    }
}
