package io.castellan.broker.storage.raft;

import io.castellan.broker.raft.LogEntry;
import io.castellan.broker.raft.RaftLog;
import io.castellan.broker.storage.CorruptRecordException;
import io.castellan.broker.storage.StorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.zip.CRC32C;

/**
 * The real, disk-backed {@link RaftLog}: every entry is appended to one growing file
 * ({@code raft.log} in the given directory), fsync'd before {@link #append} returns. Unlike
 * {@code broker-storage}'s partitioned data log ({@code PartitionLog}, backed by {@code Segment}s
 * that are deliberately <em>not</em> fsync'd per record for throughput), every write here is
 * fsync'd synchronously: {@code RaftNode} calls {@code log.append(entry)} in-line while handling
 * an {@code AppendEntries} RPC and returns a success response immediately afterward with no
 * separate flush step, so durability has to be this method's own responsibility — an entry this
 * log has acknowledged but that a crash then loses is exactly the kind of safety violation the
 * Raft paper's "write to stable storage before responding" rule exists to prevent (the same
 * category of bug {@link FilePersistentState}'s docs describe for {@code currentTerm}/{@code
 * votedFor}). {@link #truncateFrom} is fsync'd for the same reason.
 *
 * <p>Deliberately a single file rather than segmented like the data log: {@link #truncateFrom} —
 * used whenever a follower's log conflicts with a new leader's and must be rolled back — needs to
 * cut the log off at an arbitrary point, which is a single {@link FileChannel#truncate(long)} call
 * against one file but would need to reason about deleting/rewriting whole segments and partial
 * segments against a segmented layout. Raft logs are compacted independently via snapshotting (not
 * implemented here — this project's scope, like {@code broker-raft}'s own package docs on
 * membership changes, cuts snapshotting; see this module's top-level report for what that means in
 * practice) rather than Kafka-style retention/compaction, so the throughput and multi-file
 * bookkeeping benefits segmentation gives the data log don't apply here anyway.
 *
 * <p>On-disk frame, one per entry: {@code [4-byte frameLength][8-byte term][8-byte index][4-byte
 * CRC32C][4-byte commandLength][command bytes]}. An in-memory {@code List<IndexEntry>} (index
 * {@code i} holds Raft index {@code i+1}'s term and file position — entries are always contiguous,
 * never gapped like a compacted data-log segment can be) is rebuilt by scanning the file once on
 * {@link #open}; the actual command bytes are always read back from disk on demand, never cached,
 * since a command can be arbitrarily large.
 */
public final class FileRaftLog implements RaftLog, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(FileRaftLog.class);

    private static final int LENGTH_FIELD_BYTES = 4;
    private static final int HEADER_BYTES = 8 + 8 + 4;

    private final Path path;
    private final FileChannel channel;
    private final List<IndexEntry> index = new ArrayList<>();

    private record IndexEntry(long term, long filePosition) {
    }

    private FileRaftLog(Path path, FileChannel channel) {
        this.path = path;
        this.channel = channel;
    }

    public static synchronized FileRaftLog open(Path dir) {
        try {
            Files.createDirectories(dir);
            Path path = dir.resolve("raft.log");
            FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE,
                    StandardOpenOption.READ, StandardOpenOption.WRITE);
            FileRaftLog raftLog = new FileRaftLog(path, channel);
            raftLog.recover();
            return raftLog;
        } catch (IOException e) {
            throw new StorageException("failed to open raft log in " + dir, e);
        }
    }

    private void recover() throws IOException {
        long size = channel.size();
        long pos = 0;
        while (pos < size) {
            ByteBuffer lenBuf = ByteBuffer.allocate(LENGTH_FIELD_BYTES);
            if (readFully(lenBuf, pos) < LENGTH_FIELD_BYTES) {
                break;
            }
            lenBuf.flip();
            int frameLength = lenBuf.getInt();
            long frameStart = pos + LENGTH_FIELD_BYTES;
            if (frameLength < HEADER_BYTES + 4 || frameStart + frameLength > size) {
                break;
            }
            ByteBuffer frame = ByteBuffer.allocate(frameLength);
            if (readFully(frame, frameStart) < frameLength) {
                break;
            }
            frame.flip();
            long term = frame.getLong();
            long entryIndex = frame.getLong();
            int storedCrc = frame.getInt();
            byte[] rest = new byte[frameLength - HEADER_BYTES];
            frame.get(rest);
            if (!crcMatches(rest, storedCrc)) {
                break;
            }
            if (entryIndex != index.size() + 1L) {
                throw new CorruptRecordException(
                        "raft log " + path + " is non-contiguous: expected index " + (index.size() + 1)
                                + " but found " + entryIndex);
            }
            index.add(new IndexEntry(term, pos));
            pos = frameStart + frameLength;
        }
        if (pos < size) {
            log.warn("raft log {} recovered with {} trailing bytes discarded (torn write on prior crash)",
                    path, size - pos);
        }
        channel.truncate(pos);
    }

    @Override
    public synchronized long lastIndex() {
        return index.size();
    }

    @Override
    public synchronized long term(long idx) {
        if (idx == 0) {
            return 0;
        }
        if (idx < 1 || idx > index.size()) {
            throw new IllegalArgumentException("no entry at index " + idx);
        }
        return index.get((int) (idx - 1)).term();
    }

    @Override
    public synchronized Optional<LogEntry> get(long idx) {
        if (idx < 1 || idx > index.size()) {
            return Optional.empty();
        }
        return Optional.of(readEntryAt((int) (idx - 1)));
    }

    @Override
    public synchronized List<LogEntry> entriesFrom(long fromIndexInclusive) {
        if (fromIndexInclusive > lastIndex()) {
            return List.of();
        }
        long start = Math.max(fromIndexInclusive, 1);
        List<LogEntry> out = new ArrayList<>();
        for (long i = start; i <= lastIndex(); i++) {
            out.add(readEntryAt((int) (i - 1)));
        }
        return out;
    }

    private LogEntry readEntryAt(int listSlot) {
        long filePosition = index.get(listSlot).filePosition();
        try {
            ByteBuffer lenBuf = ByteBuffer.allocate(LENGTH_FIELD_BYTES);
            readFully(lenBuf, filePosition);
            lenBuf.flip();
            int frameLength = lenBuf.getInt();

            ByteBuffer frame = ByteBuffer.allocate(frameLength);
            readFully(frame, filePosition + LENGTH_FIELD_BYTES);
            frame.flip();
            long term = frame.getLong();
            long idx = frame.getLong();
            int storedCrc = frame.getInt();
            byte[] rest = new byte[frameLength - HEADER_BYTES];
            frame.get(rest);
            if (!crcMatches(rest, storedCrc)) {
                throw new CorruptRecordException("CRC mismatch reading raft log index " + idx + " from " + path);
            }
            ByteBuffer payload = ByteBuffer.wrap(rest);
            int commandLength = payload.getInt();
            byte[] command = new byte[commandLength];
            payload.get(command);
            return new LogEntry(term, idx, command);
        } catch (IOException e) {
            throw new StorageException("failed to read raft log entry from " + path, e);
        }
    }

    @Override
    public synchronized void append(LogEntry entry) {
        if (entry.index() != lastIndex() + 1) {
            throw new IllegalArgumentException(
                    "expected append at index " + (lastIndex() + 1) + " but entry has index " + entry.index());
        }
        byte[] command = entry.command();
        int payloadLength = 4 + command.length;
        ByteBuffer payload = ByteBuffer.allocate(payloadLength);
        payload.putInt(command.length);
        payload.put(command);
        payload.flip();

        CRC32C crc = new CRC32C();
        crc.update(payload.duplicate());

        int frameLength = HEADER_BYTES + payloadLength;
        ByteBuffer full = ByteBuffer.allocate(LENGTH_FIELD_BYTES + frameLength);
        full.putInt(frameLength);
        full.putLong(entry.term());
        full.putLong(entry.index());
        full.putInt((int) crc.getValue());
        full.put(payload);
        full.flip();

        try {
            long position = channel.size();
            writeFully(full, position);
            channel.force(true);
            index.add(new IndexEntry(entry.term(), position));
        } catch (IOException e) {
            throw new StorageException("failed to append entry at index " + entry.index() + " to " + path, e);
        }
    }

    @Override
    public synchronized void truncateFrom(long fromIndexInclusive) {
        if (fromIndexInclusive < 1) {
            throw new IllegalArgumentException("fromIndexInclusive must be >= 1, got " + fromIndexInclusive);
        }
        if (fromIndexInclusive > index.size()) {
            return;
        }
        long cutPosition = index.get((int) (fromIndexInclusive - 1)).filePosition();
        try {
            channel.truncate(cutPosition);
            channel.force(true);
        } catch (IOException e) {
            throw new StorageException("failed to truncate raft log " + path + " from index " + fromIndexInclusive, e);
        }
        index.subList((int) (fromIndexInclusive - 1), index.size()).clear();
    }

    @Override
    public synchronized void close() {
        try {
            channel.close();
        } catch (IOException e) {
            throw new StorageException("failed to close raft log " + path, e);
        }
    }

    private static boolean crcMatches(byte[] payload, int expected) {
        CRC32C crc = new CRC32C();
        crc.update(payload);
        return (int) crc.getValue() == expected;
    }

    private int readFully(ByteBuffer buf, long position) throws IOException {
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

    private void writeFully(ByteBuffer buf, long position) throws IOException {
        long p = position;
        while (buf.hasRemaining()) {
            p += channel.write(buf, p);
        }
    }
}
