/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.auth;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import io.izzel.arclight.common.prts.kernel.codes.WriteDisposition;
import io.izzel.arclight.common.prts.kernel.intent.IntentQueue;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class I4AuthorityTest {

    private static final String WORLD = "w";
    private static final String DOMAIN = "chunk";

    private static WriteAttempt attempt(long id, String site, long version, boolean admitted) {
        return WriteAttempt.builder(id, WORLD, site)
            .holder(HolderKind.REGISTERED, site, "thread-" + site)
            .target(WriteLevel.REGION, DOMAIN)
            .domains(Set.of(DOMAIN), Set.of(DOMAIN))
            .version(version, 100L, 0L)
            .admitted(admitted)
            .build();
    }

    private static WriteAuthority authority(OwnerRegistry owners) {
        return new WriteAuthority(owners, new IntentQueue(() -> 2, () -> 0), new WriteLedger(),
            () -> false, () -> 2);
    }

    @Test
    void ownerVersionAndUndeclaredWritesUseExistingCodes() {
        OwnerRegistry owners = new OwnerRegistry();
        WriteVersionSlots versions = new WriteVersionSlots();
        versions.refresh(true);
        long version = versions.grant(WORLD, WriteLevel.REGION, DOMAIN);
        owners.acquire(new OwnerToken(WORLD, WriteLevel.REGION, DOMAIN, version, version, 95L,
            150L, HolderKind.REGISTERED, "site:a"));
        WriteAuthority authority = authority(owners);

        assertEquals(WriteDisposition.GRANT, authority.authorize(attempt(1L, "site:a", version, true))
            .disposition());
        assertEquals(RejectCode.WRITE_DENIED_NOT_OWNER,
            authority.authorize(attempt(2L, "site:b", version, true)).code());
        assertEquals(RejectCode.VERSION_MISMATCH,
            authority.authorize(attempt(3L, "site:a", version + 1L, true)).code());
        assertEquals(RejectCode.NATIVE_UNDECLARED,
            authority.authorize(attempt(4L, "site:a", version, false)).code());
    }
}
