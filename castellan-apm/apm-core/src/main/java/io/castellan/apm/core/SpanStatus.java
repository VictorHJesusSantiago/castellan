package io.castellan.apm.core;

/** Whether a finished span's operation succeeded. See {@link Span#recordError(Throwable)}. */
public enum SpanStatus {
    OK,
    ERROR
}
