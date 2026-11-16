/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.decision;

/** The closed decision kinds an owner-local node may take; an unnamed kind is never guessed into a
 * node. */
public enum DecisionKind {

    AIR_CHANGE,
    DAMAGE,
    TARGET,
    POI_TAKE,
    POI_RELEASE,
    PLUGIN_EVENT
}
