package com.example.springgraphqlmongo.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "security.rate-limit")
public class RateLimitProperties {

	private boolean enabled = true;

	/** Apply anonymous-tier limits to unauthenticated {@code GET /actuator/health} calls. */
	private boolean limitHealthChecks = false;

	private Tier read = new Tier(120);

	private Tier ingest = new Tier(20);

	private Tier anonymous = new Tier(30);

	@Data
	public static class Tier {

		private int requestsPerMinute;

		public Tier() {
		}

		public Tier(int requestsPerMinute) {
			this.requestsPerMinute = requestsPerMinute;
		}

	}

}
