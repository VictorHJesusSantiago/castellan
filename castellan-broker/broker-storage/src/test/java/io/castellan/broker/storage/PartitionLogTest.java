package io.castellan.broker.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class PartitionLogTest {

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String str(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    @Test
    void appendsAndReadsBackInOrder(@TempDir Path dir) {
        PartitionLog log = PartitionLog.open(dir, 1024 * 1024);

        long o0 = log.append(bytes("k0"), bytes("v0"));
        long o1 = log.append(bytes("k1"), bytes("v1"));
        long o2 = log.append(null, bytes("v2"));

        assertThat(List.of(o0, o1, o2)).containsExactly(0L, 1L, 2L);
        List<Record> records = log.read(0, 10);
        assertThat(records).hasSize(3);
        assertThat(records.get(0).offset()).isZero();
        assertThat(str(records.get(0).value())).isEqualTo("v0");
        assertThat(str(records.get(1).key())).isEqualTo("k1");
        assertThat(records.get(2).key()).isNull();
        assertThat(log.latestOffset()).isEqualTo(3);

        log.close();
    }

    @Test
    void writesAndReadsAcrossASegmentBoundary(@TempDir Path dir) {
        PartitionLog log = PartitionLog.open(dir, 200);

        List<Long> offsets = IntStream.range(0, 50)
                .mapToObj(i -> log.append(bytes("key" + i), bytes("value-" + i)))
                .collect(Collectors.toList());

        assertThat(offsets).containsExactlyElementsOf(LongRange(0, 50));
        long logFileCount;
        try (var stream = java.nio.file.Files.list(dir)) {
            logFileCount = stream.filter(p -> p.toString().endsWith(".log")).count();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        assertThat(logFileCount).isGreaterThan(1);

        List<Record> all = log.read(0, 1000);
        assertThat(all).hasSize(50);
        for (int i = 0; i < 50; i++) {
            assertThat(all.get(i).offset()).isEqualTo(i);
            assertThat(str(all.get(i).value())).isEqualTo("value-" + i);
        }

        List<Record> fromMiddle = log.read(23, 10);
        assertThat(fromMiddle).hasSize(10);
        assertThat(fromMiddle.get(0).offset()).isEqualTo(23);
        assertThat(fromMiddle.get(9).offset()).isEqualTo(32);

        log.close();
    }

    private static List<Long> LongRange(int fromInclusive, int toExclusive) {
        return IntStream.range(fromInclusive, toExclusive).mapToObj(i -> (long) i).collect(Collectors.toList());
    }

    @Test
    void survivesRestartAndResumesAtTheCorrectOffset(@TempDir Path dir) {
        PartitionLog log = PartitionLog.open(dir, 500);
        for (int i = 0; i < 30; i++) {
            log.append(bytes("k" + i), bytes("v" + i));
        }
        log.close();

        PartitionLog reopened = PartitionLog.open(dir, 500);
        assertThat(reopened.latestOffset()).isEqualTo(30);
        List<Record> all = reopened.read(0, 100);
        assertThat(all).hasSize(30);
        assertThat(str(all.get(29).value())).isEqualTo("v29");

        long newOffset = reopened.append(bytes("k30"), bytes("v30"));
        assertThat(newOffset).isEqualTo(30);
        reopened.close();
    }

    @Test
    void retentionDeletesOnlyClosedSegmentsOlderThanCutoff(@TempDir Path dir) {
        PartitionLog log = PartitionLog.open(dir, 80);

        long now = System.currentTimeMillis();
        for (int i = 0; i < 5; i++) {
            log.append(bytes("old" + i), bytes("value-old-" + i), now - 100_000);
        }
        log.append(bytes("new"), bytes("value-new"), now);

        long earliestBefore = log.earliestOffset();
        assertThat(earliestBefore).isZero();

        int deleted = log.applyRetention(RetentionPolicy.byTime(60_000));
        assertThat(deleted).isGreaterThan(0);
        assertThat(log.earliestOffset()).isGreaterThan(earliestBefore);

        List<Record> remaining = log.read(log.earliestOffset(), 100);
        assertThat(remaining).isNotEmpty();
        assertThat(str(remaining.get(remaining.size() - 1).value())).isEqualTo("value-new");

        log.close();
    }

    @Test
    void compactionKeepsOnlyTheLatestRecordPerKeyAndAllNullKeyedRecords(@TempDir Path dir) {
        PartitionLog log = PartitionLog.open(dir, 60);

        log.append(bytes("a"), bytes("a-v1"));
        log.append(bytes("b"), bytes("b-v1"));
        log.append(null, bytes("tombstone-like-null-key"));
        log.append(bytes("a"), bytes("a-v2"));
        log.append(bytes("b"), bytes("b-v2"));
        log.append(bytes("filler"), bytes("f-v1"));
        log.append(bytes("c"), bytes("c-v1"));

        long beforeCount = log.read(0, 100).size();
        int dropped = log.compact();
        assertThat(dropped).isGreaterThan(0);

        List<Record> after = log.read(0, 100);
        assertThat(after.size()).isLessThan((int) beforeCount);

        assertThat(after).extracting(Record::offset).contains(2L, 3L, 4L, 5L, 6L).doesNotContain(0L, 1L);

        Record latestA = after.stream().filter(r -> r.hasKey() && str(r.key()).equals("a")).findFirst().orElseThrow();
        assertThat(str(latestA.value())).isEqualTo("a-v2");
        Record latestB = after.stream().filter(r -> r.hasKey() && str(r.key()).equals("b")).findFirst().orElseThrow();
        assertThat(str(latestB.value())).isEqualTo("b-v2");
        assertThat(after.stream().anyMatch(r -> !r.hasKey())).isTrue();

        log.close();
    }
}
