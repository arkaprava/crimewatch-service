package com.example.springgraphqlmongo.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;

public class ApiKeyAuthenticationToken extends AbstractAuthenticationToken {

	private final String apiKey;

	public ApiKeyAuthenticationToken(String apiKey, Collection<? extends GrantedAuthority> authorities) {
		super(authorities);
		this.apiKey = apiKey;
		setAuthenticated(true);
	}

	@Override
	public Object getCredentials() {
		return apiKey;
	}

	@Override
	public Object getPrincipal() {
		return "api-key-client";
	}

}
