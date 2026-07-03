package com.example.springgraphqlmongo.security;

import com.example.springgraphqlmongo.config.SecurityProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

	static final String API_KEY_HEADER = "X-API-Key";

	private static final String API_KEY_PREFIX = "ApiKey ";

	private final SecurityProperties securityProperties;

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		if (!securityProperties.isEnabled()) {
			filterChain.doFilter(request, response);
			return;
		}

		if (!requiresAuthentication(request)) {
			filterChain.doFilter(request, response);
			return;
		}

		String apiKey = resolveApiKey(request);
		if (apiKey == null) {
			filterChain.doFilter(request, response);
			return;
		}

		List<SimpleGrantedAuthority> authorities = new ArrayList<>();
		if (securityProperties.getReadKeys().contains(apiKey)) {
			authorities.add(new SimpleGrantedAuthority("ROLE_" + SecurityRoles.CRIME_READ));
		}
		if (securityProperties.getIngestKeys().contains(apiKey)) {
			authorities.add(new SimpleGrantedAuthority("ROLE_" + SecurityRoles.CRIME_INGEST));
			authorities.add(new SimpleGrantedAuthority("ROLE_" + SecurityRoles.CRIME_READ));
		}

		if (!authorities.isEmpty()) {
			SecurityContextHolder.getContext()
					.setAuthentication(new ApiKeyAuthenticationToken(apiKey, authorities));
		}

		filterChain.doFilter(request, response);
	}

	private boolean requiresAuthentication(HttpServletRequest request) {
		String path = request.getRequestURI();
		return path.startsWith("/graphql") || path.startsWith("/actuator/");
	}

	private String resolveApiKey(HttpServletRequest request) {
		String apiKey = request.getHeader(API_KEY_HEADER);
		if (apiKey != null && !apiKey.isBlank()) {
			return apiKey.trim();
		}

		String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (authorization != null && authorization.startsWith(API_KEY_PREFIX)) {
			return authorization.substring(API_KEY_PREFIX.length()).trim();
		}
		return null;
	}

}
