package com.example.springgraphqlmongo.security;

import com.example.springgraphqlmongo.config.RateLimitProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

	static final String RATE_LIMIT_LIMIT_HEADER = "X-RateLimit-Limit";

	static final String RATE_LIMIT_REMAINING_HEADER = "X-RateLimit-Remaining";

	static final String RETRY_AFTER_HEADER = "Retry-After";

	private final RateLimitProperties rateLimitProperties;

	private final RateLimitService rateLimitService;

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		if (!rateLimitProperties.isEnabled() || !shouldRateLimit(request)) {
			filterChain.doFilter(request, response);
			return;
		}

		RateLimitTier tier = resolveTier();
		String clientKey = resolveClientKey(request);
		RateLimitResult result = rateLimitService.tryConsume(clientKey, tier);

		response.setHeader(RATE_LIMIT_LIMIT_HEADER, String.valueOf(result.limit()));
		response.setHeader(RATE_LIMIT_REMAINING_HEADER, String.valueOf(result.remaining()));

		if (!result.allowed()) {
			response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
			response.setHeader(RETRY_AFTER_HEADER, String.valueOf(result.retryAfterSeconds()));
			response.setContentType(MediaType.APPLICATION_JSON_VALUE);
			response.getWriter()
					.write("{\"error\":\"Too many requests\",\"retryAfterSeconds\":" + result.retryAfterSeconds()
							+ "}");
			return;
		}

		filterChain.doFilter(request, response);
	}

	private boolean shouldRateLimit(HttpServletRequest request) {
		String path = request.getRequestURI();
		if (path.startsWith("/graphql") || path.startsWith("/actuator/")) {
			if (HttpMethod.GET.matches(request.getMethod()) && "/actuator/health".equals(path)
					&& !rateLimitProperties.isLimitHealthChecks()) {
				return false;
			}
			return true;
		}
		return false;
	}

	private RateLimitTier resolveTier() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication != null && authentication.isAuthenticated()) {
			boolean ingest = authentication.getAuthorities()
					.stream()
					.map(GrantedAuthority::getAuthority)
					.anyMatch(authority -> ("ROLE_" + SecurityRoles.CRIME_INGEST).equals(authority));
			if (ingest) {
				return RateLimitTier.INGEST;
			}
			return RateLimitTier.READ;
		}
		return RateLimitTier.ANONYMOUS;
	}

	private String resolveClientKey(HttpServletRequest request) {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication instanceof ApiKeyAuthenticationToken apiKeyAuthentication) {
			return String.valueOf(apiKeyAuthentication.getCredentials());
		}
		return request.getRemoteAddr();
	}

}
