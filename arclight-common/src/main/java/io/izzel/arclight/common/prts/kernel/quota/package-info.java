/* SPDX-License-Identifier: GPL-3.0-or-later */
/** The quota decision contract: family/level matrix, decision/receipt/token/epoch, and the bounded
 * DEVICE execution boundary the level tick consults. The contract itself is pure; the DEVICE
 * boundary acts only through the tick path that calls it and publishes no readout key of its own. */
package io.izzel.arclight.common.prts.kernel.quota;
