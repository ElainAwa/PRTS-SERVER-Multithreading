/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * Ported from the FeudalKings fork of Arclight, commit a61faa4d02249a07226b7e3fc6b70525a32c9921
 * ("fix(remapper): key the plugin class cache by the version").
 * Re-expressed on the version string this server publishes while it starts. See THIRD-PARTY.md.
 */
package io.izzel.arclight.common.prts.support;

import java.io.InputStream;
import java.net.URL;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

/**
 * The version string of the running build.
 *
 * <p>Anything cached across restarts has to be keyed by this string, because a build that changes
 * must not be served artifacts produced by an earlier one. The manifest of the launcher is the
 * authoritative source, but a class that is loaded from a plain mod file sees neither that manifest
 * nor the per package attributes, so the string published at startup is read first and the manifest
 * of the code source is read as a last resort.</p>
 */
public final class PrtsVersion {

    private static final String PROPERTY = "arclight.version";
    private static final String UNKNOWN = "unknown";

    private PrtsVersion() {
    }

    /**
     * @return the version of the running build, never null or empty
     */
    public static String version() {
        String published = System.getProperty(PROPERTY);
        if (published != null && !published.isBlank()) {
            return published;
        }
        String fromPackage = PrtsVersion.class.getPackage().getImplementationVersion();
        if (fromPackage != null && !fromPackage.isBlank()) {
            return fromPackage;
        }
        String fromManifest = readManifestVersion();
        return fromManifest == null || fromManifest.isBlank() ? UNKNOWN : fromManifest;
    }

    private static String readManifestVersion() {
        if (PrtsVersion.class.getProtectionDomain().getCodeSource() == null) {
            return null;
        }
        URL location = PrtsVersion.class.getProtectionDomain().getCodeSource().getLocation();
        if (location == null) {
            return null;
        }
        try (InputStream stream = new URL("jar:" + location + "!/META-INF/MANIFEST.MF").openStream()) {
            return new Manifest(stream).getMainAttributes().getValue(Attributes.Name.IMPLEMENTATION_VERSION);
        } catch (Exception ignored) {
            // Not running from a jar, or the manifest carries no version: the caller falls back.
            return null;
        }
    }
}
