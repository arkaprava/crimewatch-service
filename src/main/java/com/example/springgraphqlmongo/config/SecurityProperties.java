package com.example.springgraphqlmongo.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "security.api-keys")
public class SecurityProperties {

	private boolean enabled = true;

	private List<String> readKeys = new ArrayList<>();

	private List<String> ingestKeys = new ArrayList<>();

}
