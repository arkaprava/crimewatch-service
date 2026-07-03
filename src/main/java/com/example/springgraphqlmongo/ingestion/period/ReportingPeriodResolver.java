package com.example.springgraphqlmongo.ingestion.period;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ReportingPeriodResolver {

	private static final Pattern FINANCIAL_YEAR = Pattern.compile("(\\d{4})-(\\d{2,4})");

	private static final Pattern QUARTER = Pattern.compile("(\\d{4})-Q([1-4])", Pattern.CASE_INSENSITIVE);

	private static final Pattern YEAR_MONTH = Pattern.compile("(\\d{4})-(\\d{2})");

	public Instant resolveEnd(String period, ZoneId zone) {
		if (period == null || period.isBlank()) {
			return Instant.now();
		}
		String trimmed = period.trim();

		Matcher quarter = QUARTER.matcher(trimmed);
		if (quarter.matches()) {
			int year = Integer.parseInt(quarter.group(1));
			int q = Integer.parseInt(quarter.group(2));
			int month = q * 3;
			return YearMonth.of(year, month).atEndOfMonth().atTime(12, 0).atZone(zone).toInstant();
		}

		Matcher financialYear = FINANCIAL_YEAR.matcher(trimmed);
		if (financialYear.matches()) {
			int endYear = Integer.parseInt(financialYear.group(2));
			if (endYear < 100) {
				endYear += 2000;
			}
			return ZonedDateTime.of(endYear, 6, 30, 12, 0, 0, 0, zone).toInstant();
		}

		Matcher yearMonth = YEAR_MONTH.matcher(trimmed);
		if (yearMonth.matches()) {
			int year = Integer.parseInt(yearMonth.group(1));
			int month = Integer.parseInt(yearMonth.group(2));
			return YearMonth.of(year, month).atEndOfMonth().atTime(12, 0).atZone(zone).toInstant();
		}

		try {
			return LocalDate.parse(trimmed).atTime(12, 0).atZone(zone).toInstant();
		}
		catch (DateTimeParseException ignored) {
			return Instant.now();
		}
	}

}
