/* SPDX-License-Identifier: GPL-3.0-or-later */
/**
 * PRTS category: optional ServerCore layer.
 *
 * <p>Opt-in (disabled by default) and free of kernel coupling: it must be possible to delete this
 * subtree without touching anything the kernel owns.</p>
 *
 * <p>Scope is one member only: the reliable chunk-save journal, that is write-ahead recovery plus
 * chunked flush, together with the configuration keys that journal needs. Every other asset of the
 * original set is out of scope here.</p>
 *
 * <p>The chunk pipeline, entity tracking and networking seams are owned by the kernel. They must
 * never be implemented, injected into or configured from this subtree, so this layer can be
 * dropped as a whole without stranding kernel work.</p>
 *
 * <p><b>The layer occupies no mixin seam.</b> {@link
 * io.izzel.arclight.common.prts.optional.servercore.ChunkJournal} writes the unsaved chunks of a
 * cycle ahead of the region files and replays them after an unclean exit; nothing in this package
 * is a mixin, and the layer declares no mixin configuration. It is driven by platform events
 * instead - the server tick event, the level-load event and the server-stopping event - which the
 * NeoForge module subscribes to while the category is enabled. A kernel that rewrites the tick
 * loop, the world lifecycle or the shutdown path therefore does not touch this layer, and the
 * layer keeps working as long as the platform keeps firing its own events.</p>
 *
 * <p>The storage and serialization seam is a different matter: that one belongs to the kernel, so
 * a kernel that takes it over is meant to delete this subtree, journal included. The journal
 * writes only under its own {@code journal} directory and never touches the region layout, which
 * is what makes dropping it leave nothing behind.</p>
 *
 * <p>An external ServerCore installation replaces this layer; the two must not be enabled on the
 * same server.</p>
 */
package io.izzel.arclight.common.prts.optional.servercore;
