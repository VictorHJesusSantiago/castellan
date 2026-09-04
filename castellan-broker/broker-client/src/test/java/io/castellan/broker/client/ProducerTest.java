package io.castellan.broker.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProducerTest {

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
    void sendReturnsTheAssignedOffsetAndTheRecordBecomesFetchableFromAnyNode() throws Exception {
        try (Producer producer = new Producer(cluster.bootstrapNodes())) {
            ProduceResult first = producer.send("orders", 0, "k1".getBytes(), "v1".getBytes());
            assertThat(first.offset()).isEqualTo(0L);
            ProduceResult second = producer.send("orders", 0, "k2".getBytes(), "v2".getBytes());
            assertThat(second.offset()).isEqualTo(1L);
        }
    }

    @Test
    void aProducerBootstrappedFromOnlyOneNodeStillFindsTheLeaderViaMetadataDiscovery() throws Exception {
        try (Producer producer = new Producer(cluster.singleBootstrapNode())) {
            ProduceResult result = producer.send("orders", 0, null, "v".getBytes());
            assertThat(result.offset()).isEqualTo(0L);
        }
    }

    @Test
    void nonIdempotentProducerAppendsANewRecordOnEveryCallEvenWithIdenticalPayloads() throws Exception {
        try (Producer producer = new Producer(cluster.bootstrapNodes(), false)) {
            assertThat(producer.isIdempotent()).isFalse();
            ProduceResult first = producer.send("dup-topic", 0, null, "same".getBytes());
            ProduceResult second = producer.send("dup-topic", 0, null, "same".getBytes());
            assertThat(second.offset()).isEqualTo(first.offset() + 1);
        }
    }

    @Test
    void idempotentProducerAssignsIncreasingSequencesPerPartitionIndependently() throws Exception {
        try (Producer producer = new Producer(cluster.bootstrapNodes(), true)) {
            assertThat(producer.isIdempotent()).isTrue();
            ProduceResult p0First = producer.send("multi", 0, null, "a".getBytes());
            ProduceResult p1First = producer.send("multi", 1, null, "b".getBytes());
            ProduceResult p0Second = producer.send("multi", 0, null, "c".getBytes());

            assertThat(p0First.offset()).isEqualTo(0L);
            assertThat(p1First.offset()).isEqualTo(0L);
            assertThat(p0Second.offset()).isEqualTo(1L);
        }
    }

    @Test
    void sendingToAFollowerTransparentlyRedirectsToTheLeaderInsteadOfFailing() throws Exception {
        String leaderId = cluster.awaitLeader().nodeId();
        var followerAddresses = cluster.nodes.stream()
                .filter(n -> !n.id().equals(leaderId))
                .map(n -> new io.castellan.broker.protocol.client.NodeInfo(n.id(), n.host(), n.clientPort()))
                .toList();

        try (Producer producer = new Producer(followerAddresses)) {
            ProduceResult result = producer.send("orders", 0, null, "v".getBytes());
            assertThat(result.offset()).isEqualTo(0L);
        }
    }

    @Test
    void aBlankBootstrapListIsRejectedImmediately() {
        assertThatThrownBy(() -> new Producer(java.util.List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
