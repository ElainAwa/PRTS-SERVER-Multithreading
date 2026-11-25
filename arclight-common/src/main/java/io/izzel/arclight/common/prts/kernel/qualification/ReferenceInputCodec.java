/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.qualification;

import io.izzel.arclight.common.prts.kernel.random.StreamKey;
import io.izzel.arclight.common.prts.kernel.random.StreamRole;
import io.izzel.arclight.common.prts.kernel.random.StreamState;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The archival encoding of one fixed reference input. Canonical bytes use the length-prefixed UTF-8
 * and little-endian integer form of a stream key, and a trailing SHA-256 digest covers the whole
 * body, including the reversible entity state. The reversible section is appended after the older
 * fields: an older archive is refused at that named field instead of being silently read without
 * the state a replay needs. A decode that cannot verify the digest or read one named field refuses
 * with the field named instead of returning a partial input.
 */
public final class ReferenceInputCodec {

    /** The four-byte format marker at the head of every encoded input. */
    private static final int MAGIC = 0x31464952;

    /** The only format version this codec writes or reads. */
    private static final int FORMAT_VERSION = 1;

    /** The digest length in bytes. */
    private static final int DIGEST_LENGTH = 32;

    /** A bound on repeated entries and on one text component, so a damaged length cannot allocate freely. */
    private static final int MAX_ENTRIES = 1 << 20;

    /** The encoded size of one reversible entry: an entity id, nine double words and a tick count. */
    private static final int REVERSIBLE_ENTRY_BYTES = Long.BYTES + 9 * Long.BYTES + Integer.BYTES;

    /** One decode attempt: an input or a named refusal. */
    public record DecodeResult(ReferenceInput input, String failure) {

        public boolean ok() {
            return input != null && (failure == null || failure.isEmpty());
        }
    }

    private ReferenceInputCodec() {
    }

    /** Encodes one input into archival bytes with a trailing checksum. */
    public static byte[] encode(ReferenceInput input) {
        if (input == null) {
            throw new IllegalArgumentException("a reference input is required");
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream(256);
        writeInt(body, MAGIC);
        writeInt(body, FORMAT_VERSION);
        writeLong(body, input.worldSeed());
        writeString(body, input.worldId());
        writeLong(body, input.registrationVersion());
        writeLong(body, input.planTick());
        writeInt(body, input.owners());
        List<ReferenceInput.Arrival> arrivals = input.arrivals();
        writeInt(body, arrivals.size());
        for (ReferenceInput.Arrival arrival : arrivals) {
            writeLong(body, arrival.key());
            writeInt(body, arrival.entityId());
            writeLong(body, Double.doubleToRawLongBits(arrival.x()));
            writeLong(body, Double.doubleToRawLongBits(arrival.y()));
            writeLong(body, Double.doubleToRawLongBits(arrival.z()));
            writeLong(body, arrival.order());
            writeLong(body, arrival.deadlineNanos());
        }
        StreamState state = input.streamState();
        writeInt(body, state.dataVersion());
        writeStreamKey(body, state.key());
        writeLong(body, state.seedLo());
        writeLong(body, state.seedHi());
        writeLong(body, state.draws());
        body.write(state.hasGaussian() ? 1 : 0);
        writeLong(body, Double.doubleToRawLongBits(state.gaussian()));
        writeLong(body, state.forks());
        writeLong(body, input.streamPosition());
        Map<Long, Long> initialState = input.initialState();
        writeInt(body, initialState.size());
        for (Map.Entry<Long, Long> entry : initialState.entrySet()) {
            writeLong(body, entry.getKey());
            writeLong(body, entry.getValue());
        }
        Map<Long, ReferenceInput.EntityState> reversibleState = new TreeMap<>(input.reversibleState());
        writeInt(body, reversibleState.size());
        for (Map.Entry<Long, ReferenceInput.EntityState> entry : reversibleState.entrySet()) {
            ReferenceInput.EntityState entity = entry.getValue();
            writeLong(body, entry.getKey());
            writeLong(body, Double.doubleToRawLongBits(entity.x()));
            writeLong(body, Double.doubleToRawLongBits(entity.y()));
            writeLong(body, Double.doubleToRawLongBits(entity.z()));
            writeLong(body, Double.doubleToRawLongBits(entity.yRot()));
            writeLong(body, Double.doubleToRawLongBits(entity.xRot()));
            writeLong(body, Double.doubleToRawLongBits(entity.yBodyRot()));
            writeLong(body, Double.doubleToRawLongBits(entity.vx()));
            writeLong(body, Double.doubleToRawLongBits(entity.vy()));
            writeLong(body, Double.doubleToRawLongBits(entity.vz()));
            writeInt(body, entity.tickCount());
        }
        byte[] canonical = body.toByteArray();
        byte[] digest = sha256(canonical);
        byte[] encoded = new byte[canonical.length + DIGEST_LENGTH];
        System.arraycopy(canonical, 0, encoded, 0, canonical.length);
        System.arraycopy(digest, 0, encoded, canonical.length, DIGEST_LENGTH);
        return encoded;
    }

    /** Decodes archival bytes; the digest and every field are checked before an input is returned. */
    public static DecodeResult decode(byte[] bytes) {
        if (bytes == null || bytes.length < DIGEST_LENGTH + Integer.BYTES) {
            return refusal("short-input");
        }
        int bodyLength = bytes.length - DIGEST_LENGTH;
        byte[] canonical = new byte[bodyLength];
        byte[] supplied = new byte[DIGEST_LENGTH];
        System.arraycopy(bytes, 0, canonical, 0, bodyLength);
        System.arraycopy(bytes, bodyLength, supplied, 0, DIGEST_LENGTH);
        if (!MessageDigest.isEqual(sha256(canonical), supplied)) {
            return refusal("checksum");
        }
        Reader reader = new Reader(canonical);
        try {
            if (reader.intValue("format-marker") != MAGIC) {
                return refusal("format-marker");
            }
            if (reader.intValue("format-version") != FORMAT_VERSION) {
                return refusal("format-version");
            }
            long worldSeed = reader.longValue("worldSeed");
            String worldId = reader.stringValue("worldId");
            long registrationVersion = reader.longValue("registrationVersion");
            long planTick = reader.longValue("planTick");
            int owners = reader.intValue("owners");
            int arrivalCount = reader.intValue("arrivals");
            if (arrivalCount < 0 || arrivalCount > MAX_ENTRIES || arrivalCount > reader.remaining() / 52) {
                return refusal("arrivals");
            }
            List<ReferenceInput.Arrival> arrivals = new ArrayList<>(arrivalCount);
            for (int index = 0; index < arrivalCount; index++) {
                long key = reader.longValue("arrival.key");
                int entityId = reader.intValue("arrival.entityId");
                double x = Double.longBitsToDouble(reader.longValue("arrival.x"));
                double y = Double.longBitsToDouble(reader.longValue("arrival.y"));
                double z = Double.longBitsToDouble(reader.longValue("arrival.z"));
                long order = reader.longValue("arrival.order");
                long deadlineNanos = reader.longValue("arrival.deadlineNanos");
                arrivals.add(new ReferenceInput.Arrival(key, entityId, x, y, z, order, deadlineNanos));
            }
            int dataVersion = reader.intValue("streamState.dataVersion");
            StreamKey streamKey = readStreamKey(reader);
            long seedLo = reader.longValue("streamState.seedLo");
            long seedHi = reader.longValue("streamState.seedHi");
            long draws = reader.longValue("streamState.draws");
            boolean hasGaussian = reader.byteValue("streamState.hasGaussian") != 0;
            double gaussian = Double.longBitsToDouble(reader.longValue("streamState.gaussian"));
            long forks = reader.longValue("streamState.forks");
            long streamPosition = reader.longValue("streamPosition");
            int initialCount = reader.intValue("initialState");
            if (initialCount < 0 || initialCount > MAX_ENTRIES || initialCount > reader.remaining() / 16) {
                return refusal("initialState");
            }
            Map<Long, Long> initialState = new LinkedHashMap<>();
            for (int index = 0; index < initialCount; index++) {
                initialState.put(reader.longValue("initialState.key"),
                    reader.longValue("initialState.value"));
            }
            int reversibleCount = reader.intValue("reversibleState");
            if (reversibleCount < 0 || reversibleCount > MAX_ENTRIES
                || reversibleCount > reader.remaining() / REVERSIBLE_ENTRY_BYTES) {
                return refusal("reversibleState");
            }
            Map<Long, ReferenceInput.EntityState> reversibleState = new LinkedHashMap<>();
            for (int index = 0; index < reversibleCount; index++) {
                long entityId = reader.longValue("reversibleState.key");
                double x = Double.longBitsToDouble(reader.longValue("reversibleState.x"));
                double y = Double.longBitsToDouble(reader.longValue("reversibleState.y"));
                double z = Double.longBitsToDouble(reader.longValue("reversibleState.z"));
                double yRot = Double.longBitsToDouble(reader.longValue("reversibleState.yRot"));
                double xRot = Double.longBitsToDouble(reader.longValue("reversibleState.xRot"));
                double yBodyRot = Double.longBitsToDouble(reader.longValue("reversibleState.yBodyRot"));
                double vx = Double.longBitsToDouble(reader.longValue("reversibleState.vx"));
                double vy = Double.longBitsToDouble(reader.longValue("reversibleState.vy"));
                double vz = Double.longBitsToDouble(reader.longValue("reversibleState.vz"));
                int tickCount = reader.intValue("reversibleState.tickCount");
                reversibleState.put(entityId, new ReferenceInput.EntityState(x, y, z, yRot, xRot,
                    yBodyRot, vx, vy, vz, tickCount));
            }
            if (reader.remaining() != 0) {
                return refusal("trailing-bytes");
            }
            StreamState state = new StreamState(dataVersion, streamKey, seedLo, seedHi, draws,
                hasGaussian, gaussian, forks);
            ReferenceInput input = new ReferenceInput(worldSeed, worldId, registrationVersion,
                planTick, owners, arrivals, state, streamPosition, initialState, reversibleState);
            return new DecodeResult(input, "");
        } catch (Refusal refused) {
            return refusal(refused.field);
        } catch (RuntimeException invalid) {
            return refusal("invalid-field");
        }
    }

    private static void writeStreamKey(ByteArrayOutputStream out, StreamKey key) {
        writeLong(out, key.worldSeed());
        writeString(out, key.worldIdentity());
        writeString(out, key.dimension());
        writeString(out, key.business());
        writeLong(out, key.objectIdentity());
        writeString(out, key.role().code());
        writeInt(out, key.derivationVersion());
    }

    private static StreamKey readStreamKey(Reader reader) {
        long worldSeed = reader.longValue("streamKey.worldSeed");
        String worldIdentity = reader.stringValue("streamKey.worldIdentity");
        String dimension = reader.stringValue("streamKey.dimension");
        String business = reader.stringValue("streamKey.business");
        long objectIdentity = reader.longValue("streamKey.objectIdentity");
        String roleCode = reader.stringValue("streamKey.role");
        StreamRole role = null;
        for (StreamRole candidate : StreamRole.values()) {
            if (candidate.code().equals(roleCode)) {
                role = candidate;
                break;
            }
        }
        if (role == null) {
            throw new Refusal("streamKey.role");
        }
        int derivationVersion = reader.intValue("streamKey.derivationVersion");
        return new StreamKey(worldSeed, worldIdentity, dimension, business, objectIdentity, role,
            derivationVersion);
    }

    private static void writeString(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeInt(out, bytes.length);
        out.writeBytes(bytes);
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        for (int index = 0; index < Integer.BYTES; index++) {
            out.write((value >>> (Byte.SIZE * index)) & 0xFF);
        }
    }

    private static void writeLong(ByteArrayOutputStream out, long value) {
        for (int index = 0; index < Long.BYTES; index++) {
            out.write((int) (value >>> (Byte.SIZE * index)) & 0xFF);
        }
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException absent) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", absent);
        }
    }

    private static DecodeResult refusal(String field) {
        return new DecodeResult(null, "decode-failed:" + field);
    }

    /** A field-name-bearing failure, so a damaged field is answered with its own name. */
    private static final class Refusal extends RuntimeException {

        private final String field;

        Refusal(String field) {
            super(field);
            this.field = field;
        }
    }

    /** A bounded little-endian reader over the canonical body. */
    private static final class Reader {

        private final byte[] data;
        private int position;

        Reader(byte[] data) {
            this.data = data;
        }

        int remaining() {
            return data.length - position;
        }

        private void require(int count, String field) {
            if (count < 0 || remaining() < count) {
                throw new Refusal(field);
            }
        }

        int intValue(String field) {
            require(Integer.BYTES, field);
            int value = 0;
            for (int index = 0; index < Integer.BYTES; index++) {
                value |= (data[position + index] & 0xFF) << (Byte.SIZE * index);
            }
            position += Integer.BYTES;
            return value;
        }

        long longValue(String field) {
            require(Long.BYTES, field);
            long value = 0L;
            for (int index = 0; index < Long.BYTES; index++) {
                value |= (long) (data[position + index] & 0xFF) << (Byte.SIZE * index);
            }
            position += Long.BYTES;
            return value;
        }

        byte byteValue(String field) {
            require(1, field);
            return data[position++];
        }

        String stringValue(String field) {
            int length = intValue(field + ".length");
            if (length < 0 || length > MAX_ENTRIES) {
                throw new Refusal(field);
            }
            require(length, field);
            String value = new String(data, position, length, StandardCharsets.UTF_8);
            position += length;
            return value;
        }
    }
}
