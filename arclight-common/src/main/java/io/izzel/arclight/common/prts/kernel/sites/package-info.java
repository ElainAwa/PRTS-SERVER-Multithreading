/* SPDX-License-Identifier: GPL-3.0-or-later */
/**
 * The world write points this layer watches, and who is writing through them. It answers which real
 * write paths reach the judgement and which thread and holder each attempt belongs to - not how a
 * write is judged. Design reference: the write-right section.
 */
package io.izzel.arclight.common.prts.kernel.sites;
