package io.castellan.broker.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32C;

/**
 * One append-only segment of a partition's data log: a {@code <baseOffset>.log} file holding
 * length-prefixed, CRC32C-checksummed records, plus a matching {@code <baseOffset>.index} file — a
 * dense index (one 16-byte {@code (offset, filePosition)} entry per record actually present,
 * appended in lockstep with the log). Offset-to-position lookup is a binary search over the index
 * entries by offset (see {@link #read}) rather than direct {@code (offset - baseOffset)} array
 * arithmetic: after {@link PartitionLog#compact()} rewrites a segment, the offsets it contains are
 * no longer contiguous (compaction drops superseded records but — like Kafka — never renumbers the
 * survivors, since a record's offset is its stable identity for anyone tracking a consumer
 * position), so the index must tolerate gaps. A real Kafka index is sparse (one entry per K bytes,
 * not per record) to keep the index small; dense is a deliberate simplification here that keeps
 * every lookup a single binary search with no secondary scan, at the cost of a larger index file —
 * a non-issue at this project's scale.
 *
 * <p>On-disk record framing: {@code [4-byte frameLength][8-byte offset][8-byte timestamp][4-byte
 * CRC32C][4-byte keyLength or -1][key bytes][4-byte valueLength][value bytes]}. The CRC covers
 * everything from {@code keyLength} onward.
 *
 * <p><strong>Recovery</strong>: {@link #openOrCreate} always re-scans the log file from its start,
 * re-validating every record's CRC. The first record that fails to parse (length header runs past
 * EOF, or a CRC mismatch) is treated as the point a prior process crashed mid-write, and both files
 * are truncated back to the last known-good record boundary — the standard log-segment recovery
 * technique (Kafka does the same on unclean shutdown), and what makes restart-after-crash safe: a
 * torn trailing write is discarded rather than surfaced as corruption. A CRC failure encountered
 * later, during an ordinary {@link #read}, is different — that record's position is already
 * known-good from a prior successful recovery scan, so a mismatch there means real bit rot, not a
 * torn write, and is reported as {@link CorruptRecordException} instead of silently dropped.
 */
final class Segment implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(Segment.class);

    private static final int LENGTH_FIELD_BYTES = 4;
    private static final int RECORD_HEADER_BYTES = 8 + 8 + 4;
    static final int INDEX_ENTRY_BYTES = 16;

    final long baseOffset;
    private final Path logPath;
    private final Path indexPath;
    private final FileChannel logChannel;
    private final FileChannel indexChannel;

    private volatile long nextOffset;
    private volatile long recordCount;
    private volatile long newestTimestamp;

    private Segment(long baseOffset, Path logPath, Path indexPath, FileChannel logChannel,
                     FileChannel indexChannel, long nextOffset, long recordCount, long newestTimestamp) {
        this.baseOffset = baseOffset;
        this.logPath = logPath;
        this.indexPath = indexPath;
        this.logChannel = logChannel;
        this.indexChannel = indexChannel;
        this.nextOffset = nextOffset;
        this.recordCount = recordCount;
        this.newestTimestamp = newestTimestamp;
    }

    static Path logPathFor(Path dir, long baseOffset) {
        return dir.resolve(String.format("%020d.log", baseOffset));
    }

    static Path indexPathFor(Path dir, long baseOffset) {
        return dir.resolve(String.format("%020d.index", baseOffset));
    }

    static Segment openOrCreate(Path dir, long baseOffset) {
        try {
            Files.createDirectories(dir);
            Path logPath = logPathFor(dir, baseOffset);
            Path indexPath = indexPathFor(dir, baseOffset);
            FileChannel logChannel = FileChannel.open(logPath, StandardOpenOption.CREATE,
                    StandardOpenOption.READ, StandardOpenOption.WRITE);
            FileChannel indexChannel = FileChannel.open(indexPath, StandardOpenOption.CREATE,
                    StandardOpenOption.READ, StandardOpenOption.WRITE);
            RecoveryResult recovery = recover(logChannel, indexChannel, baseOffset);
            return new Segment(baseOffset, logPath, indexPath, logChannel, indexChannel,
                    recovery.nextOffset(), recovery.recordCount(), recovery.newestTimestamp());
        } catch (IOException e) {
            throw new StorageException("failed to open segment at base offset " + baseOffset + " in " + dir, e);
        }
    }

    private record RecoveryResult(long nextOffset, long recordCount, long newestTimestamp) {
    }

    private static RecoveryResult recover(FileChannel logChannel, FileChannel indexChannel, long baseOffset)
            throws IOException {
        long size = logChannel.size();
        long pos = 0;
        long lastGoodOffset = baseOffset - 1;
        long newestTimestamp = -1;
        long recordCount = 0;

        while (pos < size) {
            ByteBuffer lenBuf = ByteBuffer.allocate(LENGTH_FIELD_BYTES);
            if (readFully(logChannel, lenBuf, pos) < LENGTH_FIELD_BYTES) {
                break;
            }
            lenBuf.flip();
            int frameLength = lenBuf.getInt();
            long frameStart = pos + LENGTH_FIELD_BYTES;
            if (frameLength < RECORD_HEADER_BYTES + 8 || frameStart + frameLength > size) {
                break;
            }
            ByteBuffer frame = ByteBuffer.allocate(frameLength);
            if (readFully(logChannel, frame, frameStart) < frameLength) {
                break;
            }
            frame.flip();
            long offset = frame.getLong();
            long timestamp = frame.getLong();
            int storedCrc = frame.getInt();
            byte[] rest = new byte[frameLength - RECORD_HEADER_BYTES];
            frame.get(rest);
            if (!crcMatches(rest, storedCrc)) {
                break;
            }
            lastGoodOffset = offset;
            newestTimestamp = timestamp;
            recordCount++;
            pos = frameStart + frameLength;
        }

        if (pos < size) {
            log.warn("segment at base offset {} recovered with {} trailing bytes discarded (torn write on prior crash)",
                    baseOffset, size - pos);
        }
        logChannel.truncate(pos);
        long indexTruncateTo = recordCount * INDEX_ENTRY_BYTES;
        if (indexChannel.size() != indexTruncateTo) {
            indexChannel.truncate(indexTruncateTo);
        }

        return new RecoveryResult(lastGoodOffset + 1, recordCount, newestTimestamp);
    }

    /** Appends one record at {@code offset}, which must be strictly greater than every offset
     * already in this segment (the caller — {@link PartitionLog} — assigns offsets; gaps are
     * allowed, contiguity is not required, since a compacted replacement segment necessarily has
     * gaps). Not fsync'd per call: the partitioned data log trades per-record durability for
     * throughput (a crash can lose the last few unflushed records — acceptable for produced data a
     * client can simply resend, unlike the Raft log, see {@code FileRaftLog}, where losing an
     * already-acknowledged entry is a consensus safety violation). {@link #flush()} forces both
     * files to disk, called on segment rotation and available for tests simulating a clean
     * restart. */
    synchronized long append(long offset, long timestamp, byte[] key, byte[] value) {
        if (offset < nextOffset) {
            throw new IllegalArgumentException("offset " + offset + " is not greater than this segment's last offset "
                    + (nextOffset - 1));
        }
        int keyLength = key == null ? -1 : key.length;
        int payloadLength = 4 + Math.max(keyLength, 0) + 4 + value.length;
        ByteBuffer payload = ByteBuffer.allocate(payloadLength);
        payload.putInt(keyLength);
        if (key != null) {
            payload.put(key);
        }
        payload.putInt(value.length);
        payload.put(value);
        payload.flip();

        CRC32C crc = new CRC32C();
        crc.update(payload.duplicate());

        int frameLength = RECORD_HEADER_BYTES + payloadLength;
        ByteBuffer full = ByteBuffer.allocate(LENGTH_FIELD_BYTES + frameLength);
        full.putInt(frameLength);
        full.putLong(offset);
        full.putLong(timestamp);
        full.putInt((int) crc.getValue());
        full.put(payload);
        full.flip();

        try {
            long position = logChannel.size();
            writeFully(logChannel, full, position);

            ByteBuffer indexEntry = ByteBuffer.allocate(INDEX_ENTRY_BYTES);
            indexEntry.putLong(offset);
            indexEntry.putLong(position);
            indexEntry.flip();
            writeFully(indexChannel, indexEntry, indexChannel.size());

            nextOffset = offset + 1;
            recordCount++;
            newestTimestamp = timestamp;
            return position;
        } catch (IOException e) {
            throw new StorageException("failed to append offset " + offset + " to segment " + logPath, e);
        }
    }

    /** Records from the first offset {@code >= fromOffsetInclusive} present in this segment, up to
     * {@code maxRecords}, found by binary-searching the index (entries are sorted by offset since
     * offsets only ever increase within a segment). */
    synchronized List<Record> read(long fromOffsetInclusive, int maxRecords) {
        try {
            int entryCount = (int) (indexChannel.size() / INDEX_ENTRY_BYTES);
            int start = firstIndexAtOrAfter(fromOffsetInclusive, entryCount);
            List<Record> out = new ArrayList<>();
            for (int i = start; i < entryCount && out.size() < maxRecords; i++) {
                long filePosition = readIndexEntryFilePosition(i);
                out.add(readRecordAt(filePosition));
            }
            return out;
        } catch (IOException e) {
            throw new StorageException("failed to read from segment " + logPath, e);
        }
    }

    private int firstIndexAtOrAfter(long target, int entryCount) throws IOException {
        int lo = 0;
        int hi = entryCount - 1;
        int result = entryCount;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            long offsetAtMid = readIndexEntryOffset(mid);
            if (offsetAtMid >= target) {
                result = mid;
                hi = mid - 1;
            } else {
                lo = mid + 1;
            }
        }
        return result;
    }

    private long readIndexEntryOffset(int entryIndex) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(8);
        readFully(indexChannel, buf, (long) entryIndex * INDEX_ENTRY_BYTES);
        buf.flip();
        return buf.getLong();
    }

    private long readIndexEntryFilePosition(int entryIndex) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(8);
        readFully(indexChannel, buf, (long) entryIndex * INDEX_ENTRY_BYTES + 8);
        buf.flip();
        return buf.getLong();
    }

    private Record readRecordAt(long filePosition) throws IOException {
        ByteBuffer lenBuf = ByteBuffer.allocate(LENGTH_FIELD_BYTES);
        readFully(logChannel, lenBuf, filePosition);
        lenBuf.flip();
        int frameLength = lenBuf.getInt();

        ByteBuffer frame = ByteBuffer.allocate(frameLength);
        readFully(logChannel, frame, filePosition + LENGTH_FIELD_BYTES);
        frame.flip();
        long offset = frame.getLong();
        long timestamp = frame.getLong();
        int storedCrc = frame.getInt();
        byte[] rest = new byte[frameLength - RECORD_HEADER_BYTES];
        frame.get(rest);
        if (!crcMatches(rest, storedCrc)) {
            throw new CorruptRecordException("CRC mismatch reading offset " + offset + " from " + logPath);
        }

        ByteBuffer payload = ByteBuffer.wrap(rest);
        int keyLength = payload.getInt();
        byte[] key = null;
        if (keyLength >= 0) {
            key = new byte[keyLength];
            payload.get(key);
        }
        int valueLength = payload.getInt();
        byte[] value = new byte[valueLength];
        payload.get(value);
        return new Record(offset, timestamp, key, value);
    }

    private static boolean crcMatches(byte[] payload, int expected) {
        CRC32C crc = new CRC32C();
        crc.update(payload);
        return (int) crc.getValue() == expected;
    }

    long baseOffset() {
        return baseOffset;
    }

    /** One past the highest offset ever appended to this segment. Meaningful as "where should the
     * next sequential append go" only for a gapless (i.e. never-compacted) segment — which is
     * always true of the active segment, the only one this is used for that purpose. */
    long nextOffset() {
        return nextOffset;
    }

    long recordCount() {
        return recordCount;
    }

    long newestTimestamp() {
        return newestTimestamp;
    }

    long sizeBytes() {
        try {
            return logChannel.size();
        } catch (IOException e) {
            throw new StorageException("failed to stat segment " + logPath, e);
        }
    }

    void flush() {
        try {
            logChannel.force(true);
            indexChannel.force(true);
        } catch (IOException e) {
            throw new StorageException("failed to flush segment " + logPath, e);
        }
    }

    void deleteFiles() {
        try {
            close();
            Files.deleteIfExists(logPath);
            Files.deleteIfExists(indexPath);
        } catch (IOException e) {
            throw new StorageException("failed to delete segment " + logPath, e);
        }
    }

    @Override
    public void close() {
        try {
            logChannel.close();
            indexChannel.close();
        } catch (IOException e) {
            throw new StorageException("failed to close segment " + logPath, e);
        }
    }

    private static int readFully(FileChannel channel, ByteBuffer buf, long position) throws IOException {
        int total = 0;
        while (buf.hasRemaining()) {
            int n = channel.read(buf, position + total);
            if (n < 0) {
                break;
            }
            total += n;
        }
        return total;
    }

    private static void writeFully(FileChannel channel, ByteBuffer buf, long position) throws IOException {
        long p = position;
        while (buf.hasRemaining()) {
            p += channel.write(buf, p);
        }
    }
}
