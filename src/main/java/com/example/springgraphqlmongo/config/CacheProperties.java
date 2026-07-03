package com.example.springgraphqlmongo.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Data
@ConfigurationProperties(prefix = "cache.crime")
public class CacheProperties {

	public enum Backend {
		CAFFEINE, REDIS
	}

	private Backend backend = Backend.CAFFEINE;

	private int maximumSize = 1000;

	private Duration nearLocationTtl = Duration.ofMinutes(10);

	private Duration searchTtl = Duration.ofMinutes(15);

	private Duration byIdTtl = Duration.ofMinutes(5);

}
