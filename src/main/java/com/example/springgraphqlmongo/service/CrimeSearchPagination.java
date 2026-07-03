package com.example.springgraphqlmongo.service;

public final class CrimeSearchPagination {

	static final int DEFAULT_LIMIT = 50;

	static final int MAX_LIMIT = 200;

	private CrimeSearchPagination() {
	}

	public static int normalizeLimit(Integer limit) {
		int effectiveLimit = limit != null ? limit : DEFAULT_LIMIT;
		if (effectiveLimit <= 0) {
			throw new IllegalArgumentException("limit must be positive");
		}
		return Math.min(effectiveLimit, MAX_LIMIT);
	}

	public static int normalizeOffset(Integer offset) {
		int effectiveOffset = offset != null ? offset : 0;
		if (effectiveOffset < 0) {
			throw new IllegalArgumentException("offset must not be negative");
		}
		return effectiveOffset;
	}

}
