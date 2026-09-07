package io.castellan.broker.raft;

/**
 * An outbound {@link RaftMessage} addressed to a specific peer — every {@link RaftNode} handler
 * method returns a {@code List<Envelope>} rather than sending anything itself. This is the crux
 * of the whole module's testability: {@link RaftNode} never touches a socket, a thread, or a
 * clock, so a test can feed it inputs and assert on the exact envelopes it produces, with no
 * mocking of network or time required (see {@code RaftClusterSimulationTest}, which wires many
 * {@code RaftNode}s together purely by routing each one's returned envelopes to the addressed
 * peer's handler, entirely within one test thread).
 */
public record Envelope(String to, RaftMessage message) {
}
