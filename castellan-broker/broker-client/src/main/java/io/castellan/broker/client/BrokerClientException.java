package io.castellan.broker.client;

/** Everything that can go wrong talking to a broker cluster from this client: a socket failure,
 * exhausting the leader-redirect budget without finding one, or a server response carrying an
 * {@code ErrorCode} the caller didn't handle itself. Unchecked, matching this library's style of
 * surfacing broker/network failures as exceptions rather than checked {@code IOException}s leaking
 * NIO plumbing into every caller. */
public final class BrokerClientException extends RuntimeException {

    public BrokerClientException(String message) {
        super(message);
    }

    public BrokerClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
