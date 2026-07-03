package com.example.springgraphqlmongo.security;

import com.example.springgraphqlmongo.config.RateLimitProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitServiceTest {

	private RateLimitService rateLimitService;

	@BeforeEach
	void setUp() {
		RateLimitProperties properties = new RateLimitProperties();
		properties.getRead().setRequestsPerMinute(2);
		properties.getIngest().setRequestsPerMinute(1);
		properties.getAnonymous().setRequestsPerMinute(1);
		rateLimitService = new RateLimitService(properties);
	}

	@Test
	void allowsRequestsUpToConfiguredLimit() {
		assertThat(rateLimitService.tryConsume("client-a", RateLimitTier.READ).allowed()).isTrue();
		assertThat(rateLimitService.tryConsume("client-a", RateLimitTier.READ).allowed()).isTrue();
	}

	@Test
	void rejectsRequestsAboveConfiguredLimit() {
		rateLimitService.tryConsume("client-b", RateLimitTier.READ);
		rateLimitService.tryConsume("client-b", RateLimitTier.READ);

		RateLimitResult rejected = rateLimitService.tryConsume("client-b", RateLimitTier.READ);
		assertThat(rejected.allowed()).isFalse();
		assertThat(rejected.limit()).isEqualTo(2);
		assertThat(rejected.retryAfterSeconds()).isGreaterThanOrEqualTo(1);
	}

	@Test
	void tracksSeparateBucketsPerTierAndClient() {
		assertThat(rateLimitService.tryConsume("client-c", RateLimitTier.READ).allowed()).isTrue();
		assertThat(rateLimitService.tryConsume("client-c", RateLimitTier.INGEST).allowed()).isTrue();
	}

}
