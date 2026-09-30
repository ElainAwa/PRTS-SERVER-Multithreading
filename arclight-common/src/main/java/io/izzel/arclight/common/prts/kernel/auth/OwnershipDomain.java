/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.auth;

/**
 * The key of one write right domain.
 *
 * <p>A right is always held for one world and one level of that world, so the pair plus the
 * identity inside the level is the key the owner registry stores a token under.</p>
 *
 * @param worldId  world the right belongs to
 * @param level    level of the domain
 * @param domainId identity of the domain inside the level
 */
public record OwnershipDomain(String worldId, WriteLevel level, String domainId) {
}
