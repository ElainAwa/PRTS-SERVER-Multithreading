/* SPDX-License-Identifier: GPL-3.0-or-later */
/**
 * The resource ladder of the share budget: the five rungs a tick gives way along, each with its
 * trigger, action, signal and return condition, and the gate that decides whether a rung may come
 * back. Design reference: the degradation section.
 */
package io.izzel.arclight.common.prts.kernel.degrade;
