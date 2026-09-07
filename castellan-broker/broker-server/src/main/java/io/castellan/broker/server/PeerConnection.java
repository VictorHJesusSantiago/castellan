package io.castellan.broker.server;

import io.castellan.broker.protocol.Frame;
import io.castellan.broker.protocol.RaftMessageCodec;
import io.castellan.broker.raft.RaftMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;

/**
 * The single outbound connection {@link RaftTransport} dials to one peer, used only for sending.
 * Lazily (re)connects on the next {@link #send} after a failure rather than running a dedicated
 * reconnect thread — simple, and sufficient given Raft's own tolerance for a dropped message
 * (the next heartbeat or election-timeout retry naturally attempts to reconnect). {@code send} is
 * {@code synchronized} so two envelopes queued for the same peer in one batch (e.g. a heartbeat
 * broadcast is one {@link java.util.List} of envelopes, each sent independently) can never
 * interleave their frame bytes on the wire, even though in this project's actual usage all sends
 * already originate from the single Raft event-loop thread and are naturally serialized anyway —
 * the lock costs nothing and removes that as an assumption a future caller could violate.
 */
final class PeerConnection {

    private static final Logger log = LoggerFactory.getLogger(PeerConnection.class);

    private final NodeAddress peer;
    private SocketChannel channel;

    PeerConnection(NodeAddress peer) {
        this.peer = peer;
    }

    synchronized void send(RaftMessage message) {
        try {
            ensureConnected();
            Frame frame = RaftMessageCodec.encode(message);
            frame.writeTo(channel);
        } catch (IOException e) {
            log.debug("failed to send {} to {}: {}", message.getClass().getSimpleName(), peer.id(), e.toString());
            closeQuietly();
        }
    }

    private void ensureConnected() throws IOException {
        if (channel != null && channel.isConnected()) {
            return;
        }
        channel = SocketChannel.open(new InetSocketAddress(peer.host(), peer.raftPort()));
    }

    private void closeQuietly() {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
            }
            channel = null;
        }
    }

    synchronized void close() {
        closeQuietly();
    }
}
