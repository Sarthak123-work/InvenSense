package com.invensense.common.security;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import javax.crypto.SecretKey;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;

public class JwtUtil {

    private final SecretKey key;
    private final long accessTokenValidityMillis;

    public JwtUtil(String secret, long accessTokenValidityMillis) {
        this.key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
        this.accessTokenValidityMillis = accessTokenValidityMillis;
    }

    public String generateToken(Long userId, String email, String role, String warehouseId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", String.valueOf(userId));
        claims.put("email", email);
        claims.put("role", role);
        if (warehouseId != null) {
            claims.put("warehouseId", warehouseId);
        }
        Date now = new Date();
        Date expiry = new Date(now.getTime() + accessTokenValidityMillis);
        return Jwts.builder()
                .claims(claims)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key)
                .compact();
    }

    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
