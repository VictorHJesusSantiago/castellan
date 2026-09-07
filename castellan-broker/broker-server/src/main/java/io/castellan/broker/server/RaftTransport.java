package io.castellan.broker.server;

import io.castellan.broker.protocol.Frame;
import io.castellan.broker.protocol.RaftMessageCodec;
import io.castellan.broker.raft.AppendEntriesRequest;
import io.castellan.broker.raft.AppendEntriesResponse;
import io.castellan.broker.raft.Envelope;
import io.castellan.broker.raft.RaftMessage;
import io.castellan.broker.raft.RequestVoteRequest;
import io.castellan.broker.raft.RequestVoteResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Real network I/O for the four {@link RaftMessage} RPCs, over blocking {@link SocketChannel}s —
 * one persistent outbound connection this node dials to each peer (used only for sending, via
 * {@link #send}), and one accepted inbound connection per peer that dialed <em>this</em> node
 * (used only for receiving, one reader thread per accepted connection). Two unidirectional
 * connections per node pair rather than one multiplexed bidirectional one: simpler to reason
 * about and implement correctly than request/response correlation over a shared connection would
 * be, and Raft's RPCs are fire-and-forget from the transport's point of view anyway (a response is
 * just another independently-addressed {@link RaftMessage}, since {@code broker-raft}'s response
 * records already carry the responder's own id — see {@link #senderOf} — so the transport never
 * needs to correlate a response with the connection a request went out on).
 *
 * <p>A dropped or refused connection is swallowed and logged, never thrown to the caller: Raft is
 * explicitly designed to tolerate lost messages (a leader simply retries via its next heartbeat
 * tick; a candidate that gets no response simply loses that vote and, if it loses the election,
 * retries after its own next randomized timeout) — see {@code RaftClusterSimulationTest}'s
 * partition-and-heal scenarios for the same tolerance proven against the in-memory simulation this
 * class reproduces over real sockets.
 */
final class RaftTransport implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(RaftTransport.class);

    private final NodeAddress self;
    private final List<NodeAddress> peers;
    private final Map<String, PeerConnection> outbound = new ConcurrentHashMap<>();
    private volatile BiConsumer<String, RaftMessage> inboundHandler = (from, msg) -> { };
    private volatile boolean running;
    private ServerSocketChannel serverChannel;
    private Thread acceptThread;

    RaftTransport(NodeAddress self, List<NodeAddress> peers) {
        this.self = self;
        this.peers = peers;
    }

    void setInboundHandler(BiConsumer<String, RaftMessage> handler) {
        this.inboundHandler = handler;
    }

    void start() throws IOException {
        running = true;
        serverChannel = ServerSocketChannel.open();
        serverChannel.bind(new InetSocketAddress(self.host(), self.raftPort()));
        for (NodeAddress peer : peers) {
            outbound.put(peer.id(), new PeerConnection(peer));
        }
        acceptThread = new Thread(this::acceptLoop, "raft-accept-" + self.id());
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    void send(Envelope envelope) {
        PeerConnection connection = outbound.get(envelope.to());
        if (connection == null) {
            log.warn("{}: no known peer '{}' to send {} to", self.id(), envelope.to(), envelope.message().getClass().getSimpleName());
            return;
        }
        connection.send(envelope.message());
    }

    private void acceptLoop() {
        while (running) {
            SocketChannel channel;
            try {
                channel = serverChannel.accept();
            } catch (IOException e) {
                if (running) {
                    log.warn("{}: raft accept loop failed: {}", self.id(), e.toString());
                }
                return;
            }
            Thread reader = new Thread(() -> readLoop(channel), "raft-reader-" + self.id());
            reader.setDaemon(true);
            reader.start();
        }
    }

    private void readLoop(SocketChannel channel) {
        try {
            while (running) {
                Optional<Frame> frame = Frame.readFrom(channel);
                if (frame.isEmpty()) {
                    return;
                }
                RaftMessage message = RaftMessageCodec.decode(frame.get());
                inboundHandler.accept(senderOf(message), message);
            }
        } catch (IOException e) {
            if (running) {
                log.debug("{}: raft peer connection closed: {}", self.id(), e.toString());
            }
        } finally {
            closeQuietly(channel);
        }
    }

    private static String senderOf(RaftMessage message) {
        return switch (message) {
            case RequestVoteRequest req -> req.candidateId();
            case RequestVoteResponse resp -> resp.voterId();
            case AppendEntriesRequest req -> req.leaderId();
            case AppendEntriesResponse resp -> resp.followerId();
        };
    }

    private static void closeQuietly(SocketChannel channel) {
        try {
            channel.close();
        } catch (IOException ignored) {
        }
    }

    @Override
    public void close() {
        running = false;
        try {
            if (serverChannel != null) {
                serverChannel.close();
            }
        } catch (IOException ignored) {
        }
        outbound.values().forEach(PeerConnection::close);
        if (acceptThread != null) {
            acceptThread.interrupt();
        }
    }
}
