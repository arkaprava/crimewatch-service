package com.example.springgraphqlmongo.config;

import com.example.springgraphqlmongo.security.ApiKeyAuthenticationFilter;
import com.example.springgraphqlmongo.security.RateLimitFilter;
import com.example.springgraphqlmongo.security.SecurityRoles;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties({ SecurityProperties.class, RateLimitProperties.class })
public class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, ApiKeyAuthenticationFilter apiKeyAuthenticationFilter,
			RateLimitFilter rateLimitFilter, SecurityProperties securityProperties) throws Exception {
		http.csrf(csrf -> csrf.disable())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> {
					auth.requestMatchers(HttpMethod.GET, "/actuator/health").permitAll();
					if (!securityProperties.isEnabled()) {
						auth.anyRequest().permitAll();
					}
					else {
						auth.requestMatchers("/graphiql", "/graphiql/**", "/vendor/**").permitAll()
								.requestMatchers("/actuator/**").hasAnyRole(SecurityRoles.CRIME_READ,
										SecurityRoles.CRIME_INGEST)
								.requestMatchers("/graphql").authenticated()
								.anyRequest().denyAll();
					}
				})
				.exceptionHandling(ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
				.addFilterBefore(apiKeyAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
				.addFilterAfter(rateLimitFilter, ApiKeyAuthenticationFilter.class);
		return http.build();
	}

}
