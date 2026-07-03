package com.example.springgraphqlmongo.security;

import com.example.springgraphqlmongo.config.RateLimitProperties;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class RateLimitService {

	private static final long ONE_MINUTE_NANOS = Duration.ofMinutes(1).toNanos();

	private final RateLimitProperties properties;

	private final LoadingCache<String, TokenBucket> buckets = Caffeine.newBuilder()
			.expireAfterAccess(Duration.ofHours(2))
			.maximumSize(10_000)
			.build(this::createBucket);

	public RateLimitService(RateLimitProperties properties) {
		this.properties = properties;
	}

	public RateLimitResult tryConsume(String clientKey, RateLimitTier tier) {
		String cacheKey = tier.name() + ":" + clientKey;
		TokenBucket.ConsumptionResult result = buckets.get(cacheKey).tryConsume();
		if (result.consumed()) {
			return RateLimitResult.allowed(result.limit(), result.remaining());
		}
		return RateLimitResult.rejected(result.limit(), result.retryAfterSeconds());
	}

	private TokenBucket createBucket(String cacheKey) {
		RateLimitTier tier = RateLimitTier.valueOf(cacheKey.substring(0, cacheKey.indexOf(':')));
		int requestsPerMinute = limitFor(tier);
		return new TokenBucket(requestsPerMinute, ONE_MINUTE_NANOS);
	}

	private int limitFor(RateLimitTier tier) {
		return switch (tier) {
			case READ -> properties.getRead().getRequestsPerMinute();
			case INGEST -> properties.getIngest().getRequestsPerMinute();
			case ANONYMOUS -> properties.getAnonymous().getRequestsPerMinute();
		};
	}

}
