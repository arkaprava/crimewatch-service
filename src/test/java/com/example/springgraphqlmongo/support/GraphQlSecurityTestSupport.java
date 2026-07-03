package com.example.springgraphqlmongo.support;

import org.springframework.graphql.test.tester.HttpGraphQlTester;

public final class GraphQlSecurityTestSupport {

	public static final String READ_API_KEY = "test-read-key";

	public static final String INGEST_API_KEY = "test-ingest-key";

	public static final String API_KEY_HEADER = "X-API-Key";

	private GraphQlSecurityTestSupport() {
	}

	public static HttpGraphQlTester withReadKey(HttpGraphQlTester tester) {
		return withApiKey(tester, READ_API_KEY);
	}

	public static HttpGraphQlTester withIngestKey(HttpGraphQlTester tester) {
		return withApiKey(tester, INGEST_API_KEY);
	}

	public static HttpGraphQlTester withApiKey(HttpGraphQlTester tester, String apiKey) {
		return tester.mutate().header(API_KEY_HEADER, apiKey).build();
	}

}
