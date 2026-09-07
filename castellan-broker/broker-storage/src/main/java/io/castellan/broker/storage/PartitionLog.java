package io.castellan.broker.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A single partition's append-only data log: an ordered sequence of {@link Segment}s on disk,
 * named by starting offset (Kafka's own segment-naming convention), with the newest segment
 * ("active") accepting appends and rotating to a fresh one once it reaches {@code segmentBytes}.
 * This is the log a <em>producer</em>'s record ends up in — distinct from (but, in
 * {@code broker-server}'s wiring, driven by) the Raft replication log: a Raft {@code LogEntry}'s
 * command is what gets proposed and committed by consensus, and a committed entry's payload is
 * what this class actually appends, once per committed entry, via {@code broker-raft}'s
 * {@code ApplyListener}.
 *
 * <p>Not thread-safe beyond what {@code synchronized} on each method provides against concurrent
 * callers of this one instance — callers must not share a single directory across two
 * {@code PartitionLog} instances.
 */
public final class PartitionLog implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(PartitionLog.class);

    private final Path dir;
    private final long segmentBytesLimit;
    private final NavigableMap<Long, Segment> segments = new TreeMap<>();
    private Segment active;
    private long nextOffset;

    private PartitionLog(Path dir, long segmentBytesLimit) {
        this.dir = dir;
        this.segmentBytesLimit = segmentBytesLimit;
    }

    /** Opens (creating if necessary) the partition log rooted at {@code dir}, re-opening every
     * existing segment — each one recovers itself independently, see {@link Segment#openOrCreate}
     * — and resuming append at the offset immediately following the active segment's last record. */
    public static synchronized PartitionLog open(Path dir, long segmentBytesLimit) {
        if (segmentBytesLimit <= 0) {
            throw new IllegalArgumentException("segmentBytesLimit must be positive, got " + segmentBytesLimit);
        }
        PartitionLog partitionLog = new PartitionLog(dir, segmentBytesLimit);
        try {
            Files.createDirectories(dir);
            List<Long> baseOffsets = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.log")) {
                for (Path p : stream) {
                    String name = p.getFileName().toString();
                    baseOffsets.add(Long.parseLong(name.substring(0, name.length() - ".log".length())));
                }
            }
            if (baseOffsets.isEmpty()) {
                baseOffsets.add(0L);
            }
            baseOffsets.sort(Long::compareTo);
            for (long baseOffset : baseOffsets) {
                Segment segment = Segment.openOrCreate(dir, baseOffset);
                partitionLog.segments.put(baseOffset, segment);
            }
            partitionLog.active = partitionLog.segments.lastEntry().getValue();
            partitionLog.nextOffset = partitionLog.active.nextOffset();
        } catch (IOException e) {
            throw new StorageException("failed to open partition log at " + dir, e);
        }
        return partitionLog;
    }

    /** Appends one record, rotating to a fresh segment first if the active one has already reached
     * its size limit. Returns the offset assigned. */
    public synchronized long append(byte[] key, byte[] value) {
        return append(key, value, System.currentTimeMillis());
    }

    synchronized long append(byte[] key, byte[] value, long timestamp) {
        if (active.recordCount() > 0 && active.sizeBytes() >= segmentBytesLimit) {
            roll();
        }
        long offset = nextOffset;
        active.append(offset, timestamp, key, value);
        nextOffset = offset + 1;
        return offset;
    }

    private void roll() {
        active.flush();
        Segment fresh = Segment.openOrCreate(dir, nextOffset);
        segments.put(nextOffset, fresh);
        active = fresh;
    }

    /** Up to {@code maxRecords} records starting from the first offset {@code >=
     * fromOffsetInclusive}, spanning as many segments as needed. */
    public synchronized List<Record> read(long fromOffsetInclusive, int maxRecords) {
        if (fromOffsetInclusive >= nextOffset || maxRecords <= 0) {
            return List.of();
        }
        List<Record> out = new ArrayList<>();
        Map.Entry<Long, Segment> startEntry = segments.floorEntry(fromOffsetInclusive);
        Iterator<Segment> it = (startEntry != null ? segments.tailMap(startEntry.getKey(), true) : segments)
                .values().iterator();
        long from = fromOffsetInclusive;
        while (it.hasNext() && out.size() < maxRecords) {
            List<Record> part = it.next().read(from, maxRecords - out.size());
            out.addAll(part);
        }
        return out;
    }

    /** The oldest offset still retained (the base offset of the oldest surviving segment) — not
     * necessarily 0 once {@link #applyRetention} has deleted early segments. */
    public synchronized long earliestOffset() {
        return segments.firstKey();
    }

    /** One past the highest offset ever appended — the partition's local high-water mark. */
    public synchronized long latestOffset() {
        return nextOffset;
    }

    public synchronized long totalSizeBytes() {
        long total = 0;
        for (Segment s : segments.values()) {
            total += s.sizeBytes();
        }
        return total;
    }

    /** Deletes closed (non-active) segments that violate {@code policy}, oldest first. See
     * {@link RetentionPolicy}'s own docs for exactly what "violate" means for each bound. Returns
     * the number of segments deleted. The active segment is never a candidate — a partition always
     * keeps at least one segment, matching Kafka's own behavior (an idle partition doesn't retention
     * itself out of existence, since it can always still accept new appends). */
    public synchronized int applyRetention(RetentionPolicy policy) {
        int deleted = 0;
        if (policy.retentionMillis() > 0) {
            long cutoff = System.currentTimeMillis() - policy.retentionMillis();
            Iterator<Map.Entry<Long, Segment>> it = segments.entrySet().iterator();
            while (it.hasNext()) {
                Segment segment = it.next().getValue();
                if (segment == active) {
                    break;
                }
                if (segment.newestTimestamp() >= 0 && segment.newestTimestamp() < cutoff) {
                    log.info("retention deleting segment baseOffset={} (newestTimestamp={} < cutoff={})",
                            segment.baseOffset(), segment.newestTimestamp(), cutoff);
                    segment.deleteFiles();
                    it.remove();
                    deleted++;
                } else {
                    break;
                }
            }
        }
        if (policy.maxTotalBytes() > 0) {
            while (segments.size() > 1 && totalSizeBytes() > policy.maxTotalBytes()) {
                Map.Entry<Long, Segment> oldest = segments.firstEntry();
                if (oldest.getValue() == active) {
                    break;
                }
                log.info("retention deleting segment baseOffset={} (total size over {} bytes)",
                        oldest.getKey(), policy.maxTotalBytes());
                oldest.getValue().deleteFiles();
                segments.remove(oldest.getKey());
                deleted++;
            }
        }
        return deleted;
    }

    /**
     * Kafka-style log compaction, scoped to this partition's <em>closed</em> segments (the active
     * segment is left untouched — compacting a segment still being appended to would require
     * rewriting it while new data keeps arriving, which real compacted-topic implementations avoid
     * for the same reason). Every closed-segment record with a non-null key is kept only if it
     * holds the newest offset seen for that exact key; records with a null key are never eligible
     * for dedup and are always kept (matching Kafka's own compacted-topic rule) — the record's own
     * {@code key} field <em>is</em> the key extractor here, since every record already carries one
     * explicitly, unlike Raft log entries which wrap an opaque, uninterpreted command.
     *
     * <p>Surviving records keep their original offsets (compaction never renumbers — an offset is
     * a consumer's durable position, and shifting it out from under a paused consumer would silently
     * skip or repeat data) and are merged into a single replacement segment named after the first
     * closed segment's base offset. This is a real, working compaction pass, simplified relative to
     * Kafka's own incremental, crash-resumable cleaner in one respect: it rewrites all eligible
     * closed segments in one synchronous pass rather than incrementally cleaning one "dirty" range
     * at a time — acceptable at this project's data volumes, but it does mean a very large backlog
     * of closed segments compacts in one longer pause rather than many short ones.
     *
     * @return the number of records dropped by compaction
     */
    public synchronized int compact() {
        List<Segment> closed = new ArrayList<>();
        for (Segment s : segments.values()) {
            if (s != active) {
                closed.add(s);
            }
        }
        if (closed.isEmpty()) {
            return 0;
        }

        List<List<Record>> perSegmentRecords = new ArrayList<>();
        long originalCount = 0;
        Map<ByteBuffer, Long> lastOffsetForKey = new LinkedHashMap<>();
        for (Segment segment : closed) {
            List<Record> records = segment.read(segment.baseOffset(), Integer.MAX_VALUE);
            perSegmentRecords.add(records);
            originalCount += records.size();
            for (Record r : records) {
                if (r.hasKey()) {
                    lastOffsetForKey.put(ByteBuffer.wrap(r.key()), r.offset());
                }
            }
        }
        Set<Long> offsetsToKeep = new TreeSet<>(lastOffsetForKey.values());
        for (List<Record> records : perSegmentRecords) {
            for (Record r : records) {
                if (!r.hasKey()) {
                    offsetsToKeep.add(r.offset());
                }
            }
        }

        long newBaseOffset = closed.get(0).baseOffset();
        Path tempDir = dir.resolve(".compact-tmp");
        try {
            Files.createDirectories(tempDir);
            Files.deleteIfExists(Segment.logPathFor(tempDir, newBaseOffset));
            Files.deleteIfExists(Segment.indexPathFor(tempDir, newBaseOffset));
        } catch (IOException e) {
            throw new StorageException("failed to prepare compaction temp dir " + tempDir, e);
        }

        Segment temp = Segment.openOrCreate(tempDir, newBaseOffset);
        long retained = 0;
        for (List<Record> records : perSegmentRecords) {
            for (Record r : records) {
                if (offsetsToKeep.contains(r.offset())) {
                    temp.append(r.offset(), r.timestamp(), r.key(), r.value());
                    retained++;
                }
            }
        }
        temp.flush();
        temp.close();

        for (Segment segment : closed) {
            segments.remove(segment.baseOffset());
            segment.deleteFiles();
        }
        try {
            Files.move(Segment.logPathFor(tempDir, newBaseOffset), Segment.logPathFor(dir, newBaseOffset),
                    StandardCopyOption.REPLACE_EXISTING);
            Files.move(Segment.indexPathFor(tempDir, newBaseOffset), Segment.indexPathFor(dir, newBaseOffset),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new StorageException("failed to install compacted segment at base offset " + newBaseOffset, e);
        }
        Segment replacement = Segment.openOrCreate(dir, newBaseOffset);
        segments.put(newBaseOffset, replacement);

        int dropped = (int) (originalCount - retained);
        log.info("compaction on {}: {} records -> {} records ({} dropped)", dir, originalCount, retained, dropped);
        return dropped;
    }

    @Override
    public synchronized void close() {
        for (Segment s : segments.values()) {
            s.flush();
            s.close();
        }
    }
}
