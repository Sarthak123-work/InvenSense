package com.invensense.common.security;

import java.util.Collection;
import java.util.Collections;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import lombok.Getter;

@Getter
public class GatewayAuthentication extends AbstractAuthenticationToken {
    private final Long userId;
    private final String email;
    private final Role role;
    private final String warehouseId;

    public GatewayAuthentication(Long userId, String email, Role role, String warehouseId) {
        super(Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + role.name())));
        this.userId = userId;
        this.email = email;
        this.role = role;
        this.warehouseId = warehouseId;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() { return ""; }

    @Override
    public Object getPrincipal() { return email; }

    @Override
    public Collection<GrantedAuthority> getAuthorities() {
        return Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }
}
