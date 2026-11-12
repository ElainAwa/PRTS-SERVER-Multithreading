/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.destroystokyo.paper.profile;

import java.util.Objects;

public final class ProfileProperty {

    private final String name;
    private final String value;
    private final String signature;

    public ProfileProperty(String name, String value) {
        this(name, value, null);
    }

    public ProfileProperty(String name, String value, String signature) {
        this.name = name;
        this.value = value;
        this.signature = signature;
    }

    public String getName() {
        return this.name;
    }

    public String getValue() {
        return this.value;
    }

    public String getSignature() {
        return this.signature;
    }

    public boolean isSigned() {
        return this.signature != null;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ProfileProperty property)) {
            return false;
        }
        return Objects.equals(this.name, property.name)
            && Objects.equals(this.value, property.value)
            && Objects.equals(this.signature, property.signature);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.name, this.value, this.signature);
    }
}
