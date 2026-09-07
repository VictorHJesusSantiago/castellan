package io.castellan.rules;

/** Thrown when retracting or updating a {@link FactHandle} that is not currently in working
 * memory (already retracted, or from a different engine session). */
public final class NoSuchFactException extends RuntimeException {

    public NoSuchFactException(FactHandle handle) {
        super("no such fact in working memory: " + handle);
    }
}
