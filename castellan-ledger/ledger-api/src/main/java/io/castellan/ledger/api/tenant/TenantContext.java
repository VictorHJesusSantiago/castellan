package io.castellan.ledger.api.tenant;

import io.castellan.ledger.domain.TenantId;

/**
 * The current request's resolved {@link TenantId}, made available to controllers/services without
 * threading it through every method signature by hand. Bound and cleared per-request by
 * {@link TenantResolvingFilter} -- nothing outside that filter ever calls {@link #set}, so every
 * value read here has already passed the "is there a valid {@code X-Tenant-Id} header" check.
 *
 * <p>A {@link ThreadLocal}, not a Spring {@code @RequestScope} bean, deliberately: it works
 * identically for both request-handling threads and the fixed-delay {@code @Scheduled} outbox
 * relay thread (which has no HTTP request at all), and it avoids forcing every collaborator to be
 * request-scoped just to read the tenant.
 */
public final class TenantContext {

    private static final ThreadLocal<TenantId> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    static void set(TenantId tenantId) {
        CURRENT.set(tenantId);
    }

    static void clear() {
        CURRENT.remove();
    }

    /** The current request's tenant. @throws IllegalStateException if called outside a request
     * {@link TenantResolvingFilter} has already validated -- a bug (a new endpoint bypassing the
     * filter), never a legitimate runtime condition a caller should try to handle. */
    public static TenantId current() {
        TenantId tenantId = CURRENT.get();
        if (tenantId == null) {
            throw new IllegalStateException(
                    "no tenant bound to the current thread -- was this request routed through TenantResolvingFilter?");
        }
        return tenantId;
    }
}
