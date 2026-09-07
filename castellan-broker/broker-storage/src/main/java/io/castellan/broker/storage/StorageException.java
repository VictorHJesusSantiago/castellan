package io.castellan.broker.storage;

/** Wraps an {@link java.io.IOException} from the underlying filesystem as an unchecked exception —
 * every storage interface in this package (and {@code broker-raft}'s {@code RaftLog}/
 * {@code PersistentState}, which this module implements) is declared without checked exceptions, so
 * a disk failure has to surface as a runtime exception rather than force every caller up the stack
 * to declare {@code throws IOException}. */
public class StorageException extends RuntimeException {

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }

    public StorageException(String message) {
        super(message);
    }
}
