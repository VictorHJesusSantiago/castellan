package io.castellan.broker.storage;

/**
 * How aggressively {@link PartitionLog#applyRetention(RetentionPolicy)} deletes old, closed
 * segments. Both bounds are optional (a non-positive value disables that bound); when both are set
 * a segment is deleted if it violates <em>either</em> one. The active (currently-being-appended-to)
 * segment is never deleted regardless of policy — a partition always has at least one segment.
 *
 * @param retentionMillis delete a closed segment once its newest record is older than this many
 *                         milliseconds; {@code <= 0} disables time-based retention
 * @param maxTotalBytes    delete the oldest closed segments (in base-offset order) once the
 *                         partition's total on-disk size exceeds this; {@code <= 0} disables
 *                         size-based retention
 */
public record RetentionPolicy(long retentionMillis, long maxTotalBytes) {

    public static final RetentionPolicy DISABLED = new RetentionPolicy(-1, -1);

    public static RetentionPolicy byTime(long retentionMillis) {
        return new RetentionPolicy(retentionMillis, -1);
    }

    public static RetentionPolicy bySize(long maxTotalBytes) {
        return new RetentionPolicy(-1, maxTotalBytes);
    }
}
