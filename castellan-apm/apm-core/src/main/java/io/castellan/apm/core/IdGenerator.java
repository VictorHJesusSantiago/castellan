package io.castellan.apm.core;

import java.security.SecureRandom;

/**
 * Generates W3C-trace-context-shaped ids: a 16-byte (32 lowercase hex character) trace id and an
 * 8-byte (16 lowercase hex character) span id. The spec (and every backend that parses
 * {@code traceparent} headers) treats an all-zero id as "absent", so a generator that could ever
 * hand out all zeroes would silently corrupt propagation; {@link SecureRandom} makes that
 * astronomically unlikely on its own, but we still check-and-retry rather than rely on
 * "astronomically unlikely" for something this load-bearing.
 */
final class IdGenerator {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private static final SecureRandom RANDOM = new SecureRandom();

    private IdGenerator() {
    }

    static String newTraceId() {
        return newHexId(16);
    }

    static String newSpanId() {
        return newHexId(8);
    }

    private static String newHexId(int numBytes) {
        byte[] bytes = new byte[numBytes];
        String hex;
        do {
            RANDOM.nextBytes(bytes);
            hex = toHex(bytes);
        } while (isAllZero(bytes));
        return hex;
    }

    private static boolean isAllZero(byte[] bytes) {
        for (byte b : bytes) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }
}
