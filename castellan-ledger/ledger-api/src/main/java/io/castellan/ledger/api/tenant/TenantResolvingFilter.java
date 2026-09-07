package io.castellan.ledger.api.tenant;

import io.castellan.ledger.domain.TenantId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Every request must carry {@code X-Tenant-Id} (a UUID) identifying which tenant's data it may
 * touch -- strict data partitioning by tenant is enforced at exactly this boundary (see
 * {@link TenantId}'s own docs), not left to individual controllers to remember. A request with no
 * header, a blank header, or a header that isn't a valid UUID never reaches a controller at all;
 * it's rejected here with 400 and a small JSON body, before any handler method (and therefore
 * before {@link TenantContext#current()} could ever be called with nothing bound).
 */
@Component
public final class TenantResolvingFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Tenant-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HEADER_NAME);
        if (header == null || header.isBlank()) {
            rejectMissingHeader(response);
            return;
        }
        TenantId tenantId;
        try {
            tenantId = TenantId.of(header.trim());
        } catch (IllegalArgumentException malformed) {
            rejectMalformedHeader(response, header);
            return;
        }
        try {
            TenantContext.set(tenantId);
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private void rejectMissingHeader(HttpServletResponse response) throws IOException {
        writeError(response, "missing required header: " + HEADER_NAME);
    }

    private void rejectMalformedHeader(HttpServletResponse response, String header) throws IOException {
        writeError(response, HEADER_NAME + " must be a UUID, got: " + header);
    }

    private void writeError(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"" + message.replace("\"", "'") + "\"}");
    }
}
