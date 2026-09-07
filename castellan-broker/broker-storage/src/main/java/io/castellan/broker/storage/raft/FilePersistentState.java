package io.castellan.broker.storage.raft;

import io.castellan.broker.raft.PersistentState;
import io.castellan.broker.storage.StorageException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/**
 * The real, disk-backed {@link PersistentState}. {@link #save} writes a small fixed-format file —
 * {@code [8-byte currentTerm][1-byte hasVotedFor][4-byte votedForLength][votedFor UTF-8 bytes]} —
 * and calls {@link FileChannel#force(boolean)} before returning, exactly matching what that
 * interface's own docs demand: "a real implementation... must block until the write is durable
 * (fsync'd) before returning, or this guarantee is only theater". Every call to {@link #save}
 * rewrites the <em>whole</em> file from position 0 (it's a handful of bytes; there is no benefit to
 * an append-only design here, unlike {@link FileRaftLog}) and then truncates to the new length, so
 * a save that shrinks {@code votedFor} (going from a name back to none) can't leave stale trailing
 * bytes that a naive length-prefixed reader might misparse.
 *
 * <p>Losing {@code votedFor} across a restart is the specific, concrete failure this class exists
 * to prevent: without it, a server that crashes right after voting for candidate A in term 5,
 * restarts, and then receives a {@code RequestVote} from a different candidate B still in term 5
 * would vote again — Raft's one-vote-per-term rule is what makes "a majority agreed" mean anything,
 * and a double vote in the same term is exactly how two servers can both believe they won the same
 * election.
 */
public final class FilePersistentState implements PersistentState, AutoCloseable {

    private final Path path;
    private final FileChannel channel;

    private long currentTerm;
    private String votedFor;

    private FilePersistentState(Path path, FileChannel channel, long currentTerm, String votedFor) {
        this.path = path;
        this.channel = channel;
        this.currentTerm = currentTerm;
        this.votedFor = votedFor;
    }

    public static synchronized FilePersistentState open(Path dir) {
        try {
            Files.createDirectories(dir);
            Path path = dir.resolve("raft-state.bin");
            FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE,
                    StandardOpenOption.READ, StandardOpenOption.WRITE);
            long term = 0;
            String votedFor = null;
            if (channel.size() > 0) {
                ByteBuffer buf = ByteBuffer.allocate((int) channel.size());
                readFully(channel, buf, 0);
                buf.flip();
                term = buf.getLong();
                boolean hasVotedFor = buf.get() != 0;
                if (hasVotedFor) {
                    int len = buf.getInt();
                    byte[] bytes = new byte[len];
                    buf.get(bytes);
                    votedFor = new String(bytes, StandardCharsets.UTF_8);
                }
            }
            return new FilePersistentState(path, channel, term, votedFor);
        } catch (IOException e) {
            throw new StorageException("failed to open persistent state in " + dir, e);
        }
    }

    @Override
    public synchronized long currentTerm() {
        return currentTerm;
    }

    @Override
    public synchronized Optional<String> votedFor() {
        return Optional.ofNullable(votedFor);
    }

    @Override
    public synchronized void save(long currentTerm, String votedFor) {
        byte[] votedForBytes = votedFor == null ? null : votedFor.getBytes(StandardCharsets.UTF_8);
        int length = 8 + 1 + 4 + (votedForBytes == null ? 0 : votedForBytes.length);
        ByteBuffer buf = ByteBuffer.allocate(length);
        buf.putLong(currentTerm);
        buf.put((byte) (votedForBytes == null ? 0 : 1));
        buf.putInt(votedForBytes == null ? 0 : votedForBytes.length);
        if (votedForBytes != null) {
            buf.put(votedForBytes);
        }
        buf.flip();
        try {
            writeFully(channel, buf, 0);
            channel.truncate(length);
            channel.force(true);
        } catch (IOException e) {
            throw new StorageException("failed to persist raft state to " + path, e);
        }
        this.currentTerm = currentTerm;
        this.votedFor = votedFor;
    }

    @Override
    public synchronized void close() {
        try {
            channel.close();
        } catch (IOException e) {
            throw new StorageException("failed to close persistent state " + path, e);
        }
    }

    private static void readFully(FileChannel channel, ByteBuffer buf, long position) throws IOException {
        int total = 0;
        while (buf.hasRemaining()) {
            int n = channel.read(buf, position + total);
            if (n < 0) {
                break;
            }
            total += n;
        }
    }

    private static void writeFully(FileChannel channel, ByteBuffer buf, long position) throws IOException {
        long p = position;
        while (buf.hasRemaining()) {
            p += channel.write(buf, p);
        }
    }
}
