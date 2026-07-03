package com.example.springgraphqlmongo.cache;

import com.example.springgraphqlmongo.domain.CrimeStatus;
import com.example.springgraphqlmongo.domain.CrimeType;
import com.example.springgraphqlmongo.service.CrimeIncidentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class CrimeCacheKeyGeneratorTest {

	private CrimeCacheKeyGenerator keyGenerator;

	private Method findNearMethod;

	private Method searchMethod;

	private Method getByIdMethod;

	@BeforeEach
	void setUp() throws NoSuchMethodException {
		keyGenerator = new CrimeCacheKeyGenerator();
		findNearMethod = CrimeIncidentService.class.getMethod("findNear", double.class, double.class, double.class,
				String.class);
		searchMethod = CrimeIncidentService.class.getMethod("search", String.class, String.class, String.class,
				CrimeType.class, CrimeStatus.class, Integer.class, Integer.class);
		getByIdMethod = CrimeIncidentService.class.getMethod("getById", String.class);
	}

	@Test
	void roundsNearbyCoordinatesToSharedKey() {
		Object keyA = keyGenerator.generate(null, findNearMethod, -33.8688123, 151.2093456, 5.0, "NSW");
		Object keyB = keyGenerator.generate(null, findNearMethod, -33.8688999, 151.2093999, 5.0, "NSW");

		assertThat(keyA).isEqualTo(keyB);
		assertThat(keyA).isEqualTo("-33.869:151.209:5.0:nsw");
	}

	@Test
	void normalizesSearchFilters() {
		Object key = keyGenerator.generate(null, searchMethod, " Sydney ", " nsw ", null, CrimeType.THEFT,
				CrimeStatus.REPORTED, 50, 0);

		assertThat(key).isEqualTo("sydney:nsw::THEFT:REPORTED:50:0");
	}

	@Test
	void includesPaginationInSearchKey() {
		Object keyA = keyGenerator.generate(null, searchMethod, null, "NSW", null, null, null, 10, 0);
		Object keyB = keyGenerator.generate(null, searchMethod, null, "NSW", null, null, null, 10, 20);

		assertThat(keyA).isEqualTo(":nsw:::null:null:10:0");
		assertThat(keyB).isEqualTo(":nsw:::null:null:10:20");
		assertThat(keyA).isNotEqualTo(keyB);
	}

	@Test
	void usesIdForSingleIncidentLookup() {
		Object key = keyGenerator.generate(null, getByIdMethod, "abc-123");

		assertThat(key).isEqualTo("abc-123");
	}

}
