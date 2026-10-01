/* SPDX-License-Identifier: GPL-3.0-or-later */
/**
 * Create interoperability: the funnel pickup guard and the tree cutter reader guard.
 *
 * <p>Both patches answer one question for that mod - which blocks its machines may read or take
 * while the chunk in question is not loaded - and both apply only while the mod is present. They
 * sit together because they are one concern of one mod, not two unrelated fixes. Design reference:
 * the release-facing mod compatibility rounds.</p>
 */
package io.izzel.arclight.common.prts.modsupport.create;
