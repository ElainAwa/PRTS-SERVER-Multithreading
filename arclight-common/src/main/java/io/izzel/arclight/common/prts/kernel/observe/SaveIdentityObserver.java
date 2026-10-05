/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/**
 * Names the save a run was made on: the worlds that are loaded, the region file the players of each
 * world are in, and a content hash of the world directory.
 *
 * <p>The hash of the world manifest is taken once per process and then cached, because it walks
 * every region file; the hash of the level data file is taken at each read, because that file is
 * rewritten when the server saves. The manifest is the sorted list of {@code size name} lines of the
 * region files, so the same world hashes the same on another host.
 */
public final class SaveIdentityObserver {

    /** One loaded world as it was seen at the read. */
    public record World(String id, int players, String regionId, int loadedChunks) {
    }

    private static final String REGION_SUFFIX = ".mca";

    private volatile MinecraftServer source;
    private volatile String levelDatMd5 = "-";
    private volatile String manifestHash = "-";
    private volatile long manifestFiles;
    private volatile long manifestBytes;
    private volatile boolean manifestTaken;

    public void source(MinecraftServer server) {
        this.source = server;
    }

    public boolean installed() {
        return source != null;
    }

    /** @return one row per loaded world; empty while no server has been seen */
    public List<World> worlds() {
        MinecraftServer server = source;
        List<World> worlds = new ArrayList<>();
        if (server == null) {
            return worlds;
        }
        for (ServerLevel level : server.getAllLevels()) {
            String id = level.dimension().location().toString();
            List<net.minecraft.server.level.ServerPlayer> players = level.players();
            BlockPos position = players.isEmpty() ? level.getSharedSpawnPos()
                : players.get(0).blockPosition();
            String region = "r." + (position.getX() >> 9) + "." + (position.getZ() >> 9);
            worlds.add(new World(id, players.size(), region,
                level.getChunkSource().getLoadedChunksCount()));
        }
        return worlds;
    }

    public String levelDatMd5() {
        MinecraftServer server = source;
        if (server == null) {
            return "-";
        }
        try {
            Path file = server.getWorldPath(LevelResource.LEVEL_DATA_FILE);
            if (Files.isRegularFile(file)) {
                levelDatMd5 = digest(Files.readAllBytes(file));
            }
        } catch (IOException | RuntimeException ignored) {
            levelDatMd5 = "unreadable";
        }
        return levelDatMd5;
    }

    public String manifestHash() {
        takeManifest();
        return manifestHash;
    }

    public long manifestFiles() {
        takeManifest();
        return manifestFiles;
    }

    public long manifestBytes() {
        takeManifest();
        return manifestBytes;
    }

    public boolean manifestTaken() {
        return manifestTaken;
    }

    private synchronized void takeManifest() {
        if (manifestTaken) {
            return;
        }
        manifestTaken = true;
        MinecraftServer server = source;
        if (server == null) {
            return;
        }
        Path root = server.getWorldPath(LevelResource.ROOT);
        if (!Files.isDirectory(root)) {
            manifestHash = "missing";
            return;
        }
        StringBuilder manifest = new StringBuilder();
        long files = 0L;
        long bytes = 0L;
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> regions = new ArrayList<>();
            walk.filter(Files::isRegularFile).forEach(path -> {
                if (path.getFileName().toString().endsWith(REGION_SUFFIX)) {
                    regions.add(path);
                }
            });
            regions.sort((left, right) -> left.toString().compareTo(right.toString()));
            for (Path region : regions) {
                long size = Files.size(region);
                manifest.append(size).append(' ').append(root.relativize(region)).append('\n');
                files++;
                bytes += size;
            }
        } catch (IOException | RuntimeException ignored) {
            manifestHash = "unreadable";
            return;
        }
        manifestFiles = files;
        manifestBytes = bytes;
        manifestHash = digest(manifest.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String digest(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException | RuntimeException ignored) {
            return "unavailable";
        }
    }

    public synchronized void reset() {
        levelDatMd5 = "-";
        manifestHash = "-";
        manifestFiles = 0L;
        manifestBytes = 0L;
        manifestTaken = false;
    }
}
