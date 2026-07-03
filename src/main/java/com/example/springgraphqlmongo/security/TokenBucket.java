package com.example.springgraphqlmongo.security;

import java.util.concurrent.atomic.AtomicLong;

final class TokenBucket {

	private final int capacity;

	private final long refillIntervalNanos;

	private final AtomicLong tokens;

	private volatile long lastRefillNanos;

	TokenBucket(int capacity, long refillIntervalNanos) {
		this.capacity = capacity;
		this.refillIntervalNanos = refillIntervalNanos;
		this.tokens = new AtomicLong(capacity);
		this.lastRefillNanos = System.nanoTime();
	}

	synchronized ConsumptionResult tryConsume() {
		refill();
		long remaining = tokens.get();
		if (remaining <= 0) {
			long nanosToWait = refillIntervalNanos - (System.nanoTime() - lastRefillNanos);
			return ConsumptionResult.rejected(capacity, Math.max(1, nanosToWait / 1_000_000_000));
		}
		tokens.decrementAndGet();
		return ConsumptionResult.allowed(capacity, remaining - 1);
	}

	private void refill() {
		long now = System.nanoTime();
		long elapsed = now - lastRefillNanos;
		if (elapsed < refillIntervalNanos) {
			return;
		}
		long periods = elapsed / refillIntervalNanos;
		lastRefillNanos += periods * refillIntervalNanos;
		long refilled = Math.min(capacity, tokens.get() + periods * capacity);
		tokens.set(refilled);
	}

	record ConsumptionResult(boolean consumed, long limit, long remaining, long retryAfterSeconds) {

		static ConsumptionResult allowed(long limit, long remaining) {
			return new ConsumptionResult(true, limit, remaining, 0);
		}

		static ConsumptionResult rejected(long limit, long retryAfterSeconds) {
			return new ConsumptionResult(false, limit, 0, retryAfterSeconds);
		}

	}

}
