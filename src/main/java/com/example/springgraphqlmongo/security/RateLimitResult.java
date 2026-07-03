package com.example.springgraphqlmongo.security;

public record RateLimitResult(boolean allowed, long limit, long remaining, long retryAfterSeconds) {

	public static RateLimitResult allowed(long limit, long remaining) {
		return new RateLimitResult(true, limit, remaining, 0);
	}

	public static RateLimitResult rejected(long limit, long retryAfterSeconds) {
		return new RateLimitResult(false, limit, 0, retryAfterSeconds);
	}

}
