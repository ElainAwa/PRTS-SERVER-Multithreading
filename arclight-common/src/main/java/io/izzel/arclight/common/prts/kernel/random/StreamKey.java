/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.random;

import net.minecraft.world.level.levelgen.RandomSupport;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/**
 * The stable key of one deterministic shared stream.
 *
 * <p>The key names the world seed, the persistent world identity, the dimension key, the business
 * kind, the stable object identity (an entity identity or a packed chunk coordinate), the stream
 * role and the derivation version. Its encoding is a little-endian int byte length followed by UTF-8
 * bytes for every string and little-endian integers for every number; the first 128 bits of the
 * SHA-256 of those bytes are the generator's initial state, and an all-zero state is fixed by
 * setting its lowest bit. Object hash codes, thread identity and the clock are never part of the
 * key, so the same logical stream has the same state on every run and every machine.
 */
public record StreamKey(
    long worldSeed,
    String worldIdentity,
    String dimension,
    String business,
    long objectIdentity,
    StreamRole role,
    int derivationVersion) {

    /** The derivation version this library writes; a different encoding needs a new version. */
    public static final int CURRENT_DERIVATION_VERSION = 1;

    public StreamKey {
        Objects.requireNonNull(worldIdentity, "worldIdentity");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(business, "business");
        Objects.requireNonNull(role, "role");
        if (worldIdentity.isEmpty() || dimension.isEmpty() || business.isEmpty()) {
            throw new IllegalArgumentException("stream key text components must not be empty");
        }
        if (derivationVersion <= 0) {
            throw new IllegalArgumentException("derivationVersion must be positive");
        }
    }

    /**
     * A primary-role key at the current derivation version, that is the object's own shared stream.
     *
     * @param worldSeed the world's initial seed
     * @param worldIdentity a persistent world identity, never an object hash
     * @param dimension the dimension key, for example {@code minecraft:overworld}
     * @param business the stable business kind that owns the stream
     * @param objectIdentity a stable object identity or a packed chunk coordinate
     * @return the key of the object's own stream
     */
    public static StreamKey of(long worldSeed, String worldIdentity, String dimension, String business,
        long objectIdentity) {
        return new StreamKey(worldSeed, worldIdentity, dimension, business, objectIdentity, StreamRole.PRIMARY,
            CURRENT_DERIVATION_VERSION);
    }

    /**
     * This key with another role; every other component stays unchanged.
     *
     * @param newRole the role of the returned key
     * @return a key with the same name and the given role
     */
    public StreamKey withRole(StreamRole newRole) {
        return new StreamKey(worldSeed, worldIdentity, dimension, business, objectIdentity,
            Objects.requireNonNull(newRole, "newRole"), derivationVersion);
    }

    /**
     * The initial 128-bit generator state of this key: the first 128 bits of the SHA-256 of the
     * encoding, with an all-zero state replaced by setting its lowest bit.
     *
     * @return the initial generator state
     */
    public RandomSupport.Seed128bit deriveSeed() {
        return seedFromDigest(digest(encoded()));
    }

    /**
     * The canonical key encoding. A persistence layer may store these bytes instead of the record;
     * they are also the bytes the initial state is derived from.
     *
     * @return the encoded key
     */
    public byte[] encoded() {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64);
        writeLong(out, worldSeed);
        writeString(out, worldIdentity);
        writeString(out, dimension);
        writeString(out, business);
        writeLong(out, objectIdentity);
        writeString(out, role.code());
        writeInt(out, derivationVersion);
        return out.toByteArray();
    }

    /**
     * Applies the fixed state rule to a SHA-256 digest: the first 16 bytes are two little-endian
     * longs, and when both are zero the lowest bit of the low word is set.
     *
     * @param digest a digest of at least 16 bytes
     * @return the fixed initial state
     */
    static RandomSupport.Seed128bit seedFromDigest(byte[] digest) {
        if (digest.length < 16) {
            throw new IllegalArgumentException("digest shorter than 128 bits");
        }
        long low = readLong(digest, 0);
        long high = readLong(digest, 8);
        if ((low | high) == 0L) {
            low = 1L;
        }
        return new RandomSupport.Seed128bit(low, high);
    }

    private static byte[] digest(byte[] encoded) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(encoded);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }

    private static void writeString(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeInt(out, bytes.length);
        out.writeBytes(bytes);
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        for (int i = 0; i < Integer.BYTES; i++) {
            out.write((value >>> (Byte.SIZE * i)) & 0xFF);
        }
    }

    private static void writeLong(ByteArrayOutputStream out, long value) {
        for (int i = 0; i < Long.BYTES; i++) {
            out.write((int) (value >>> (Byte.SIZE * i)) & 0xFF);
        }
    }

    private static long readLong(byte[] source, int offset) {
        long value = 0L;
        for (int i = 0; i < Long.BYTES; i++) {
            value |= (long) (source[offset + i] & 0xFF) << (Byte.SIZE * i);
        }
        return value;
    }
}
