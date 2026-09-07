package io.castellan.rules.network;

import io.castellan.rules.FactHandle;

/** One fact currently sitting in an {@link AlphaNode}'s memory, paired with its handle. */
public record AlphaEntry(Object fact, FactHandle handle) {
}
