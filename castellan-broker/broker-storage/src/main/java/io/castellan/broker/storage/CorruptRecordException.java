package io.castellan.broker.storage;

/** A record's stored CRC32C didn't match its bytes on read/recovery, at a position that isn't the
 * tail of the file — i.e. not the "process crashed mid-write" case {@link Segment}'s recovery scan
 * already tolerates by truncating a trailing partial record, but actual bit-rot or a corrupted
 * file. Distinguished from a generic {@link StorageException} so callers (and operators) can tell
 * "unrecoverable data corruption" apart from "ordinary I/O failure". */
public final class CorruptRecordException extends StorageException {

    public CorruptRecordException(String message) {
        super(message);
    }
}
