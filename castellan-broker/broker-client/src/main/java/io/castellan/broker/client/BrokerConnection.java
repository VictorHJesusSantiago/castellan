package io.castellan.broker.client;

import io.castellan.broker.protocol.Frame;
import io.castellan.broker.protocol.client.ClientMessage;
import io.castellan.broker.protocol.client.ClientProtocolCodec;
import io.castellan.broker.protocol.client.NodeInfo;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;

/**
 * One lazily-opened, lazily-reopened TCP connection to a single broker node's client port —
 * matching {@code broker-server}'s own documented design of one connection per client serving
 * requests strictly one-at-a-time (see {@code ClientRequestServer}'s class docs), so callers here
 * must serialize their own calls the same way (which {@link LeaderRouter} and this class's
 * {@code synchronized call} both do).
 *
 * <p>Any I/O failure closes and discards the underlying socket so the next call transparently
 * reconnects rather than reusing a channel already known to be broken.
 */
final class BrokerConnection implements Closeable {

    private final NodeInfo node;
    private SocketChannel channel;

    BrokerConnection(NodeInfo node) {
        this.node = node;
    }

    NodeInfo node() {
        return node;
    }

    synchronized ClientMessage call(ClientMessage request) {
        try {
            SocketChannel ch = channel();
            ClientProtocolCodec.encode(request).writeTo(ch);
            Frame response = Frame.readFrom(ch)
                    .orElseThrow(() -> new IOException("connection to " + node.id() + " closed by peer"));
            return ClientProtocolCodec.decode(response);
        } catch (IOException | RuntimeException e) {
            closeQuietly();
            throw new BrokerClientException(
                    "request to " + node.id() + " (" + node.host() + ":" + node.clientPort() + ") failed: " + e, e);
        }
    }

    private SocketChannel channel() throws IOException {
        if (channel == null || !channel.isConnected()) {
            channel = SocketChannel.open(new InetSocketAddress(node.host(), node.clientPort()));
        }
        return channel;
    }

    @Override
    public synchronized void close() {
        closeQuietly();
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
}
