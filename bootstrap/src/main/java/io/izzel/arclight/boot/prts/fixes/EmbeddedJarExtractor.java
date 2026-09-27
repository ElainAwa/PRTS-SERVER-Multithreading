/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * Ported from two upstream implementations of the same asset, merged into one here:
 * PRTS 1.21.1 commit 5a5ee426ab2f6ccfa089de0b14612f8641832843 ("refresh embedded jars by content
 * instead of version") and commit f3d21388737653a9ccd2be5f9b69f3d94c0cbd17 from the FeudalKings
 * fork of Arclight ("refresh changed embedded jars"). Attribution is recorded in NOTICE and
 * THIRD-PARTY.md.
 */
package io.izzel.arclight.boot.prts.fixes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Extracts an embedded jar into a cache directory, replacing the cached copy whenever the embedded
 * bytes differ.
 *
 * <p>The version string alone is not a reliable cache key: two builds can share a version while
 * their content differs, and a stale cache entry then silently shadows the freshly built jar until
 * {@code .arclight} is deleted by hand. Comparing content removes that manual step.</p>
 *
 * <p>PRTS category: fixes (docs/PRTS-CONVENTIONS.md, C-003).</p>
 */
public final class EmbeddedJarExtractor {

    private EmbeddedJarExtractor() {
    }

    /**
     * Copies {@code source} to {@code directory/fileName}, replacing an existing file only when its
     * content differs, and deletes stale siblings afterwards.
     *
     * @param source    stream of the embedded jar; consumed by this call
     * @param directory cache directory, created when missing
     * @param fileName  name of the cached file
     * @param force     when {@code true}, replaces the cached file even if the bytes match
     * @return the path of the cached file
     * @throws IOException when the stream cannot be read or the directory cannot be written
     */
    public static Path extract(InputStream source, Path directory, String fileName, boolean force)
        throws IOException {
        Files.createDirectories(directory);
        Path target = directory.resolve(fileName);
        Path temporary = Files.createTempFile(directory, fileName, ".tmp");
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            if (force || !Files.exists(target) || Files.mismatch(temporary, target) != -1) {
                replace(temporary, target);
            }
            try (var files = Files.list(directory)) {
                for (Path old : files.toList()) {
                    if (!old.equals(target) && !old.equals(temporary)) {
                        Files.delete(old);
                    }
                }
            }
            return target;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void replace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
