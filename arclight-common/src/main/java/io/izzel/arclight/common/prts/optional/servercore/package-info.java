/* SPDX-License-Identifier: GPL-3.0-or-later */
/**
 * Optional ServerCore layer, disabled by default and free of kernel coupling: the reliable chunk-save
 * journal, driven by platform events rather than a mixin seam. It writes only under its own journal
 * directory, so the subtree can be dropped without stranding kernel work, and it must not be enabled
 * together with an external ServerCore installation.
 */
package io.izzel.arclight.common.prts.optional.servercore;
