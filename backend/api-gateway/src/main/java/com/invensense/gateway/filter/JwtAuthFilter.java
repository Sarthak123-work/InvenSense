package com.invensense.gateway.filter;

import java.nio.charset.StandardCharsets;
import java.util.List;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@Slf4j
@Component
public class JwtAuthFilter implements GlobalFilter, Ordered {

    private static final List<String> PUBLIC_PATHS = List.of(
            "/api/auth/login", "/api/auth/register", "/api/auth/refresh");

    private final SecretKey key;

    public JwtAuthFilter(@Value("${jwt.secret}") String secret) {
        this.key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        if (PUBLIC_PATHS.stream().anyMatch(path::startsWith)) {
            return chain.filter(exchange);
        }

        String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return unauthorized(exchange, "Missing or invalid Authorization header");
        }

        String token = authHeader.substring(7);
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            String userId = claims.getSubject();
            String email = claims.get("email", String.class);
            String role = claims.get("role", String.class);
            String warehouseId = claims.get("warehouseId", String.class);

            ServerHttpRequest modified = request.mutate()
                    .header("X-User-Id", userId)
                    .header("X-User-Email", email != null ? email : "")
                    .header("X-User-Role", role != null ? role : "SALES")
                    .header("X-Warehouse-Id", warehouseId != null ? warehouseId : "")
                    .build();

            return chain.filter(exchange.mutate().request(modified).build());

        } catch (Exception e) {
            log.warn("JWT validation failed: {}", e.getMessage());
            return unauthorized(exchange, "Invalid or expired token");
        }
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"timestamp\":\"" + java.time.Instant.now() + "\",\"status\":401,\"error\":\"Unauthorized\",\"message\":\"" + message + "\",\"path\":\"" + exchange.getRequest().getURI().getPath() + "\"}";
        var buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
