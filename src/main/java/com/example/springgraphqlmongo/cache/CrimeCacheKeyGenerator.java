package com.example.springgraphqlmongo.cache;

import com.example.springgraphqlmongo.domain.CrimeStatus;
import com.example.springgraphqlmongo.domain.CrimeType;
import com.example.springgraphqlmongo.service.CrimeSearchPagination;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Normalises cache keys for crime read queries. Geo keys round coordinates to
 * three decimal places (~110 m) so nearby requests share an entry.
 */
@Component("crimeCacheKeyGenerator")
public class CrimeCacheKeyGenerator implements KeyGenerator {

	private static final int COORD_PRECISION = 3;

	@Override
	public Object generate(Object target, Method method, Object... params) {
		return switch (method.getName()) {
			case "findNear" -> nearKey(params);
			case "search" -> searchKey(params);
			case "getById" -> params[0];
			default -> throw new IllegalArgumentException("Unsupported cached method: " + method.getName());
		};
	}

	private static String nearKey(Object[] params) {
		double latitude = (Double) params[0];
		double longitude = (Double) params[1];
		double radiusKm = (Double) params[2];
		String state = params.length > 3 ? normalizeBlankable((String) params[3]) : "";
		return round(latitude) + ":" + round(longitude) + ":" + radiusKm + ":" + state;
	}

	private static String searchKey(Object[] params) {
		String city = normalizeBlankable((String) params[0]);
		String state = normalizeBlankable((String) params[1]);
		String source = normalizeBlankable((String) params[2]);
		CrimeType crimeType = (CrimeType) params[3];
		CrimeStatus status = (CrimeStatus) params[4];
		int limit = CrimeSearchPagination.normalizeLimit(params.length > 5 ? (Integer) params[5] : null);
		int offset = CrimeSearchPagination.normalizeOffset(params.length > 6 ? (Integer) params[6] : null);
		return city + ":" + state + ":" + source + ":" + crimeType + ":" + status + ":" + limit + ":" + offset;
	}

	private static String normalizeBlankable(String value) {
		if (value == null || value.isBlank()) {
			return "";
		}
		return value.trim().toLowerCase(Locale.ROOT);
	}

	private static double round(double value) {
		double factor = Math.pow(10, COORD_PRECISION);
		return Math.round(value * factor) / factor;
	}

}
