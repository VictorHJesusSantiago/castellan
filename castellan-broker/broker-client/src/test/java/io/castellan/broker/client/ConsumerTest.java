package io.castellan.broker.client;

import io.castellan.broker.protocol.client.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class ConsumerTest {

    @TempDir
    Path tempDir;

    private ClientTestCluster cluster;

    @BeforeEach
    void startCluster() throws Exception {
        cluster = ClientTestCluster.start(tempDir, 3);
    }

    @AfterEach
    void stopCluster() {
        if (cluster != null) {
            cluster.close();
        }
    }

    @Test
    void aSoleMemberIsAssignedEveryPartitionOfItsSubscribedTopics() {
        try (Consumer consumer = new Consumer(cluster.bootstrapNodes(), "g-solo", List.of("orders"), 10_000)) {
            List<TopicPartition> assigned = consumer.join();
            assertThat(assigned).hasSize(3);
            assertThat(assigned).extracting(TopicPartition::topic).containsOnly("orders");
        }
    }

    @Test
    void pollReturnsProducedRecordsAndAdvancesThePositionSoASecondPollDoesNotRepeatThem() throws Exception {
        try (Producer producer = new Producer(cluster.bootstrapNodes())) {
            producer.send("poll-topic", 0, "k1".getBytes(), "v1".getBytes());
            producer.send("poll-topic", 0, "k2".getBytes(), "v2".getBytes());
        }

        try (Consumer consumer = new Consumer(cluster.bootstrapNodes(), "g-poll", List.of("poll-topic"), 10_000)) {
            consumer.join();
            List<ConsumerRecord> first = pollUntilNonEmptyFor(consumer, "poll-topic", 0);

            assertThat(first).extracting(ConsumerRecord::value)
                    .containsExactly("v1".getBytes(), "v2".getBytes());

            List<ConsumerRecord> second = consumer.poll(10).stream()
                    .filter(r -> r.partition() == 0)
                    .toList();
            assertThat(second).isEmpty();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<ConsumerRecord> pollUntilNonEmptyFor(Consumer consumer, String topic, int partition) throws InterruptedException {
        List<ConsumerRecord>[] holder = new List[1];
        ClientTestCluster.assertEventually("records to become fetchable", Duration.ofSeconds(5), () -> {
            List<ConsumerRecord> records = consumer.poll(10).stream()
                    .filter(r -> r.topic().equals(topic) && r.partition() == partition)
                    .toList();
            if (records.isEmpty()) {
                throw new AssertionError("no records yet");
            }
            holder[0] = records;
        });
        return holder[0];
    }

    @Test
    void committedOffsetIsHonoredByAFreshConsumerInstanceJoiningTheSameGroupLater() throws Exception {
        try (Producer producer = new Producer(cluster.bootstrapNodes())) {
            producer.send("resume-topic", 0, null, "a".getBytes());
            producer.send("resume-topic", 0, null, "b".getBytes());
            producer.send("resume-topic", 0, null, "c".getBytes());
        }

        try (Consumer first = new Consumer(cluster.bootstrapNodes(), "g-resume", List.of("resume-topic"), 10_000)) {
            first.join();
            List<ConsumerRecord> records = pollUntilNonEmptyFor(first, "resume-topic", 0);
            assertThat(records).hasSizeGreaterThanOrEqualTo(1);
            first.commit(new TopicPartition("resume-topic", 0), records.get(0).offset() + 1);
        }

        try (Consumer second = new Consumer(cluster.bootstrapNodes(), "g-resume", List.of("resume-topic"), 10_000)) {
            second.join();
            List<ConsumerRecord> resumed = pollUntilNonEmptyFor(second, "resume-topic", 0);
            assertThat(resumed.get(0).offset()).isEqualTo(1L);
            assertThat(resumed).extracting(ConsumerRecord::value)
                    .containsExactly("b".getBytes(), "c".getBytes());
        }
    }

    @Test
    void twoMembersOfTheSameGroupSplitAllPartitionsWithNoOverlapAndNoGap() {
        try (Consumer c1 = new Consumer(cluster.bootstrapNodes(), "g-split", List.of("split-topic"), 10_000);
             Consumer c2 = new Consumer(cluster.bootstrapNodes(), "g-split", List.of("split-topic"), 10_000)) {
            List<TopicPartition> a1 = c1.join();
            List<TopicPartition> a2 = c2.join();
            c1.heartbeat();
            a1 = c1.assignment();

            assertThat(a1.size() + a2.size()).isEqualTo(3);
            Set<TopicPartition> union = new java.util.HashSet<>(a1);
            union.addAll(a2);
            assertThat(union).hasSize(3);
        }
    }

    @Test
    void rebalanceListenerReportsAssignmentOnJoinAndRevocationOnLeave() {
        List<String> events = new ArrayList<>();
        RebalanceListener listener = new RebalanceListener() {
            @Override
            public void onPartitionsRevoked(List<TopicPartition> partitions) {
                events.add("revoked:" + partitions.size());
            }

            @Override
            public void onPartitionsAssigned(List<TopicPartition> partitions) {
                events.add("assigned:" + partitions.size());
            }
        };

        try (Consumer c1 = new Consumer(cluster.bootstrapNodes(), "g-listener", List.of("listener-topic"), 10_000, listener)) {
            List<TopicPartition> assigned = c1.join();
            assertThat(assigned).hasSize(3);
            assertThat(events).containsExactly("assigned:3");

            c1.leave();
            assertThat(events).containsExactly("assigned:3", "revoked:3");
        }
    }

    @Test
    void heartbeatAfterAnotherMembersMissedSessionTimeoutReturnsAllPartitionsToTheSurvivor() throws Exception {
        try (Consumer doomed = new Consumer(cluster.bootstrapNodes(), "g-evict", List.of("evict-topic"), 300);
             Consumer survivor = new Consumer(cluster.bootstrapNodes(), "g-evict", List.of("evict-topic"), 60_000)) {
            doomed.join();
            survivor.join();
            assertThat(survivor.assignment().size()).isLessThan(3);

            ClientTestCluster.assertEventually("survivor to reclaim every partition after doomed's eviction",
                    Duration.ofSeconds(5), () -> {
                        survivor.heartbeat();
                        assertThat(survivor.assignment()).hasSize(3);
                    });
        }
    }

    @Test
    void allDefaultPartitionsAreCoveredAcrossPartitionIndices() {
        try (Consumer consumer = new Consumer(cluster.bootstrapNodes(), "g-count", List.of("count-topic"), 10_000)) {
            List<Integer> partitions = consumer.join().stream().map(TopicPartition::partition).sorted().toList();
            assertThat(partitions).isEqualTo(IntStream.range(0, 3).boxed().collect(Collectors.toList()));
        }
    }
}
