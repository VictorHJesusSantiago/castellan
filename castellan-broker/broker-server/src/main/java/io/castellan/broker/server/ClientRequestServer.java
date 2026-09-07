package io.castellan.broker.server;

import io.castellan.broker.protocol.Frame;
import io.castellan.broker.protocol.client.ClientMessage;
import io.castellan.broker.protocol.client.ClientProtocolCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Optional;

/**
 * The client-facing listener: one accepted connection per client, each served by its own thread
 * looping "read one request frame, handle it (possibly blocking on a Raft commit — see
 * {@link BrokerRequestHandler}), write one response frame" for as long as the connection stays
 * open — a simple synchronous request/response protocol per connection (no pipelining, no
 * correlation ids needed), matching {@code broker-client}'s own single-connection-per-broker,
 * one-request-in-flight-at-a-time design. A slow request only blocks its own connection's thread,
 * never another client's.
 */
final class ClientRequestServer implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(ClientRequestServer.class);

    private final NodeAddress self;
    private final BrokerRequestHandler handler;
    private volatile boolean running;
    private ServerSocketChannel serverChannel;
    private Thread acceptThread;

    ClientRequestServer(NodeAddress self, BrokerRequestHandler handler) {
        this.self = self;
        this.handler = handler;
    }

    void start() throws IOException {
        running = true;
        serverChannel = ServerSocketChannel.open();
        serverChannel.bind(new InetSocketAddress(self.host(), self.clientPort()));
        acceptThread = new Thread(this::acceptLoop, "client-accept-" + self.id());
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    private void acceptLoop() {
        while (running) {
            SocketChannel channel;
            try {
                channel = serverChannel.accept();
            } catch (IOException e) {
                if (running) {
                    log.warn("{}: client accept loop failed: {}", self.id(), e.toString());
                }
                return;
            }
            Thread worker = new Thread(() -> serveConnection(channel), "client-conn-" + self.id());
            worker.setDaemon(true);
            worker.start();
        }
    }

    private void serveConnection(SocketChannel channel) {
        try {
            while (running) {
                Optional<Frame> frame = Frame.readFrom(channel);
                if (frame.isEmpty()) {
                    return;
                }
                ClientMessage request = ClientProtocolCodec.decode(frame.get());
                ClientMessage response = handler.handle(request);
                ClientProtocolCodec.encode(response).writeTo(channel);
            }
        } catch (IOException e) {
            log.debug("{}: client connection closed: {}", self.id(), e.toString());
        } catch (RuntimeException e) {
            log.warn("{}: error handling client request: {}", self.id(), e.toString());
        } finally {
            closeQuietly(channel);
        }
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
        if (acceptThread != null) {
            acceptThread.interrupt();
        }
    }
}
