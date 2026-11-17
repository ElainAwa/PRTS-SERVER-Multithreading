/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.chunk.save;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** The single writer of one region file: writes of one region are serialized, an encoding whose
 * target revision is older than the written one is refused without touching the file, the same
 * revision is an idempotent no-op, and a short write, disk-full or I/O failure keeps its unfinished
 * obligation instead of moving the written revision. */
public final class RegionSingleWriter {

    /** What one write request did. */
    public enum Outcome {
        WRITTEN,
        STALE_REVISION,
        IDEMPOTENT,
        FAILED
    }

    /** Why one write failed; a failure never advances the written revision. */
    public enum Failure {
        NONE,
        SHORT_WRITE,
        DISK_FULL,
        IO_ERROR
    }

    /** The result of one write request. */
    public record Result(Outcome outcome, long targetRevision, long writtenRevision, Failure failure) {
    }

    /** One encoding write; a short write is a failure the writer keeps as an obligation. */
    @FunctionalInterface
    public interface Write {

        void write() throws IOException;
    }

    /** A write that lost bytes instead of failing the whole call. */
    public static final class ShortWriteException extends IOException {

        public ShortWriteException(String message) {
            super(message);
        }
    }

    private static final class Region {

        private long writtenRevision = -1L;
        private long written;
    }

    private final Map<String, Region> regions = new LinkedHashMap<>();
    private long staleRejected;
    private long idempotent;
    private long shortWrites;
    private long diskFull;
    private long ioErrors;
    private int activeWriters;
    private int maxActiveWriters;

    /** One serialized write of one region file under one target revision. */
    public Result write(String regionFile, long targetRevision, Write write) {
        Objects.requireNonNull(regionFile, "regionFile");
        Objects.requireNonNull(write, "write");
        if (targetRevision < 0L) {
            throw new IllegalArgumentException("a target revision cannot be negative");
        }
        Region region;
        synchronized (regions) {
            region = regions.computeIfAbsent(regionFile, key -> new Region());
        }
        synchronized (region) {
            if (targetRevision < region.writtenRevision) {
                staleRejected++;
                return new Result(Outcome.STALE_REVISION, targetRevision, region.writtenRevision,
                    Failure.NONE);
            }
            if (targetRevision == region.writtenRevision) {
                idempotent++;
                return new Result(Outcome.IDEMPOTENT, targetRevision, region.writtenRevision,
                    Failure.NONE);
            }
            activeWriters++;
            maxActiveWriters = Math.max(maxActiveWriters, activeWriters);
            try {
                write.write();
            } catch (ShortWriteException shortWrite) {
                shortWrites++;
                return new Result(Outcome.FAILED, targetRevision, region.writtenRevision,
                    Failure.SHORT_WRITE);
            } catch (java.nio.file.FileSystemException full) {
                diskFull++;
                return new Result(Outcome.FAILED, targetRevision, region.writtenRevision,
                    Failure.DISK_FULL);
            } catch (IOException io) {
                ioErrors++;
                return new Result(Outcome.FAILED, targetRevision, region.writtenRevision,
                    Failure.IO_ERROR);
            } finally {
                activeWriters--;
            }
            region.writtenRevision = targetRevision;
            region.written++;
            return new Result(Outcome.WRITTEN, targetRevision, region.writtenRevision,
                Failure.NONE);
        }
    }

    public synchronized long writtenRevision(String regionFile) {
        Region region = regions.get(regionFile);
        return region == null ? -1L : region.writtenRevision;
    }

    public synchronized long staleRejected() {
        return staleRejected;
    }

    public synchronized long idempotent() {
        return idempotent;
    }

    public synchronized long shortWrites() {
        return shortWrites;
    }

    public synchronized long diskFull() {
        return diskFull;
    }

    public synchronized long ioErrors() {
        return ioErrors;
    }

    public synchronized int maxActiveWriters() {
        return maxActiveWriters;
    }
}
