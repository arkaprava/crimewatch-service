package com.example.springgraphqlmongo.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class ApiAuthorizationService {

	public void requireRead() {
		requireRole(SecurityRoles.CRIME_READ);
	}

	public void requireIngest() {
		requireRole(SecurityRoles.CRIME_INGEST);
	}

	private void requireRole(String role) {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !authentication.isAuthenticated()) {
			throw new AccessDeniedException("Authentication required");
		}

		String authority = "ROLE_" + role;
		boolean allowed = authentication.getAuthorities()
				.stream()
				.map(GrantedAuthority::getAuthority)
				.anyMatch(authority::equals);
		if (!allowed) {
			throw new AccessDeniedException("Access is denied");
		}
	}

}
