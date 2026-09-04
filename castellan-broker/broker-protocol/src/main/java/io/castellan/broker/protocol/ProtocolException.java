package io.castellan.broker.protocol;

/** An unchecked exception for anything wrong with bytes coming off the wire: a corrupt or
 * truncated frame, an unrecognized message-type byte, or a payload whose declared field lengths
 * don't add up — the network-facing analog of {@code broker-storage}'s {@code StorageException}
 * hierarchy for on-disk corruption. Declared unchecked for the same reason: every codec method in
 * this package is called from tight, exception-heavy I/O loops where a checked exception on every
 * decode call would force every caller up the stack to declare it. */
public class ProtocolException extends RuntimeException {

    public ProtocolException(String message) {
        super(message);
    }

    public ProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
