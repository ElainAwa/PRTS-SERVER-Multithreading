/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.program;

import io.izzel.arclight.common.prts.kernel.domain.entity.EntityRowFields;
import io.izzel.arclight.common.prts.kernel.domain.entity.EntityRowIdentity;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** The one live row a read permit was checked against, as plain numbers: the lifecycle generation,
 * the state revision and the kinematic values the compute-only body may read. The snapshot exists
 * only while the read is checked; the program never keeps the live object. */
public record LiveRowSnapshot(EntityRowIdentity identity, long objectGeneration, long revision,
                              double x, double y, double z, double yaw, double pitch,
                              double velX, double velY, double velZ,
                              Set<ReadPermit.FieldGroup> availableGroups,
                              EntityRowFields fields) {

    public LiveRowSnapshot {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(availableGroups, "availableGroups");
        Objects.requireNonNull(fields, "fields");
        availableGroups = Set.copyOf(availableGroups);
        if (objectGeneration < 0L || revision < 0L) {
            throw new IllegalArgumentException("a live row generation cannot be negative");
        }
    }

    /** The snapshot without the extended matrix: the kinematic-only compatibility form. */
    public LiveRowSnapshot(EntityRowIdentity identity, long objectGeneration, long revision,
                           double x, double y, double z, double yaw, double pitch, double velX,
                           double velY, double velZ,
                           Set<ReadPermit.FieldGroup> availableGroups) {
        this(identity, objectGeneration, revision, x, y, z, yaw, pitch, velX, velY, velZ,
            availableGroups, EntityRowFields.unbound());
    }

    /** The snapshot of one row whose every declared field group the host could read. */
    public static LiveRowSnapshot full(EntityRowIdentity identity, long objectGeneration,
                                       long revision, double x, double y, double z, double yaw,
                                       double pitch, double velX, double velY, double velZ) {
        return new LiveRowSnapshot(identity, objectGeneration, revision, x, y, z, yaw, pitch,
            velX, velY, velZ, Set.of(ReadPermit.FieldGroup.values()), EntityRowFields.unbound());
    }

    /** The snapshot of one really captured row: its permit groups are exactly the groups the
     * capture bound, so a permit can never name a field the frozen row does not carry. */
    public static LiveRowSnapshot captured(EntityRowIdentity identity, long objectGeneration,
                                           long revision, double x, double y, double z,
                                           double yaw, double pitch, double velX, double velY,
                                           double velZ, EntityRowFields fields) {
        Objects.requireNonNull(fields, "fields");
        Set<ReadPermit.FieldGroup> groups = new LinkedHashSet<>(List.of(
            ReadPermit.FieldGroup.IDENTITY, ReadPermit.FieldGroup.POSITION,
            ReadPermit.FieldGroup.ROTATION, ReadPermit.FieldGroup.MOTION));
        for (String group : fields.boundGroups()) {
            groups.add(ReadPermit.FieldGroup.valueOf(group));
        }
        return new LiveRowSnapshot(identity, objectGeneration, revision, x, y, z, yaw, pitch,
            velX, velY, velZ, groups, fields);
    }
}
