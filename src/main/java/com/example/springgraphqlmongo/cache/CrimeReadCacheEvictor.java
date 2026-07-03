package com.example.springgraphqlmongo.cache;

import com.example.springgraphqlmongo.config.CrimeCacheNames;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;

@Service
public class CrimeReadCacheEvictor {

	@Caching(evict = {
			@CacheEvict(cacheNames = CrimeCacheNames.CRIMES_NEAR, allEntries = true),
			@CacheEvict(cacheNames = CrimeCacheNames.CRIME_SEARCH, allEntries = true),
			@CacheEvict(cacheNames = CrimeCacheNames.CRIME_BY_ID, allEntries = true) })
	public void evictAll() {
	}

}
