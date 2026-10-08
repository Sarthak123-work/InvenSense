package com.invensense.common.security;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Filter used inside downstream services. The API Gateway validates the JWT and
 * injects X-User-* headers. This filter reads those headers and builds the
 * Spring Authentication object so controllers can use @PreAuthorize.
 */
public class GatewayAuthFilter extends OncePerRequestFilter {

    private static final String HEADER_USER_ID = "X-User-Id";
    private static final String HEADER_USER_EMAIL = "X-User-Email";
    private static final String HEADER_USER_ROLE = "X-User-Role";
    private static final String HEADER_WAREHOUSE_ID = "X-Warehouse-Id";

    private static final List<String> PUBLIC_PATHS = List.of(
            "/api/auth/login", "/api/auth/register", "/api/auth/refresh");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();

        if (PUBLIC_PATHS.stream().anyMatch(path::startsWith)) {
            filterChain.doFilter(request, response);
            return;
        }

        String userIdHeader = request.getHeader(HEADER_USER_ID);
        if (userIdHeader != null && !userIdHeader.isBlank()) {
            Long userId = Long.parseLong(userIdHeader);
            String email = request.getHeader(HEADER_USER_EMAIL);
            String roleStr = request.getHeader(HEADER_USER_ROLE);
            String warehouseId = request.getHeader(HEADER_WAREHOUSE_ID);

            Role role = roleStr != null ? Role.valueOf(roleStr) : Role.SALES;
            GatewayAuthentication auth = new GatewayAuthentication(userId, email, role, warehouseId);
            SecurityContextHolder.getContext().setAuthentication(auth);
        }

        filterChain.doFilter(request, response);
    }
}
