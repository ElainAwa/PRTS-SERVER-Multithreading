/* SPDX-License-Identifier: GPL-3.0-or-later */
/**
 * The entity domain: the frozen row shape, the pure step a worker runs, the live read and write of
 * one row, the payload of a settled batch and the frame the pipeline hands to a worker. Separate
 * from the dispatch pipeline so the kernel can be read without the entity model, and so a domain can
 * grow its own vocabulary without widening the pipeline. Design reference: the entity domain section.
 */
package io.izzel.arclight.common.prts.kernel.domain.entity;
