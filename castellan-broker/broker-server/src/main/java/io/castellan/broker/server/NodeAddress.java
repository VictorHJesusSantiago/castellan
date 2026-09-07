package io.castellan.broker.server;

/**
 * One cluster member's identity and the two ports it listens on: {@code raftPort} for Raft
 * peer-to-peer RPCs ({@link RaftTransport}) and {@code clientPort} for the client-facing protocol
 * ({@link ClientRequestServer}) — kept as two separate listeners rather than one multiplexed port
 * so a connection's own listening socket already tells the accepting side which codec applies
 * (see {@code io.castellan.broker.protocol.MessageType}'s docs), with no message-type sniffing
 * needed.
 *
 * <p>Parsed from the CLI/config string form {@code id@host:raftPort:clientPort}, e.g.
 * {@code n0@127.0.0.1:9100:9101}.
 */
public record NodeAddress(String id, String host, int raftPort, int clientPort) {

    public NodeAddress {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
    }

    public static NodeAddress parse(String spec) {
        int at = spec.indexOf('@');
        if (at < 0) {
            throw new IllegalArgumentException(
                    "expected id@host:raftPort:clientPort, got: " + spec);
        }
        String id = spec.substring(0, at);
        String[] parts = spec.substring(at + 1).split(":");
        if (parts.length != 3) {
            throw new IllegalArgumentException(
                    "expected id@host:raftPort:clientPort, got: " + spec);
        }
        return new NodeAddress(id, parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
    }
}
