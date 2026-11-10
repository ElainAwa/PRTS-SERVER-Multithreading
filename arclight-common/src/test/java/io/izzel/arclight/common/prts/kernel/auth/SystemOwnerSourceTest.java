/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class SystemOwnerSourceTest {

    @Test
    void systemOwnerTokenCarriesGrantedVersionAndExpires() {
        OwnerRegistry owners = new OwnerRegistry();
        WriteVersionSlots versions = new WriteVersionSlots();
        versions.refresh(true);
        SystemOwnerSource source = new SystemOwnerSource(owners, versions);
        OwnerToken token = source.acquire("w", "chunk", 10L, 20L);
        assertNotNull(token);
        assertEquals(20L, token.expireTick());
        assertEquals(source.lookup("w", "chunk"), token);
        assertNotNull(source.acquire("w", "chunk", 10L, 20L), "same kernel owner is reacquired");
        assertEquals(1, source.reclaimExpired(20L));
        assertNull(source.lookup("w", "chunk"));
    }
}
