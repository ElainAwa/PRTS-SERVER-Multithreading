/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OwnerRegistryOverlapTest {

    @Test
    void differentHoldersCannotOwnAParentAndAChildOfOneDomain() {
        OwnerRegistry owners = new OwnerRegistry();
        long tick = 10L;
        assertTrue(owners.acquire(token(WriteLevel.REGION, 1L, "site:a", tick)));
        assertFalse(owners.acquire(token(WriteLevel.SECTION, 2L, "site:b", tick)),
            "a child right held by another holder must be refused at acquire");
        assertTrue(owners.lookup("w", WriteLevel.REGION, "d").isPresent(),
            "the parent right must remain installed");
    }

    @Test
    void oneHolderMayOwnAParentAndAChildOfTheSameDomain() {
        OwnerRegistry owners = new OwnerRegistry();
        long tick = 10L;
        assertTrue(owners.acquire(token(WriteLevel.REGION, 1L, "site:a", tick)));
        assertTrue(owners.acquire(token(WriteLevel.SECTION, 2L, "site:a", tick)),
            "the same holder may cover both levels");
    }

    @Test
    void disjointDomainsAtDifferentLevelsDoNotConflict() {
        OwnerRegistry owners = new OwnerRegistry();
        long tick = 10L;
        assertTrue(owners.acquire(token(WriteLevel.REGION, 1L, "site:a", tick)));
        assertTrue(owners.acquire(new OwnerToken("w", WriteLevel.SECTION, "other", 2L, 7L, tick,
            tick + 20L, HolderKind.REGISTERED, "site:b")));
    }

    private static OwnerToken token(WriteLevel level, long epoch, String site, long tick) {
        return new OwnerToken("w", level, "d", epoch, 7L, tick, tick + 20L,
            HolderKind.REGISTERED, site);
    }
}
