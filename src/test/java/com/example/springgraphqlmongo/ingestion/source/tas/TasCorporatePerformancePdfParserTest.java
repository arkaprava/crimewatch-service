package com.example.springgraphqlmongo.ingestion.source.tas;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TasCorporatePerformancePdfParserTest {

	@Test
	void parsesDistrictAndDivisionRowsFromSamplePdf() throws Exception {
		Path pdf = new ClassPathResource("tas/corporate-performance-report-sample.pdf").getFile().toPath();
		var rows = TasCorporatePerformancePdfParser.parse(pdf);
		assertThat(rows).isNotEmpty();
		assertThat(rows.getFirst().reportPeriod()).matches("[A-Za-z]{3} \\d{4}");
		assertThat(rows.stream().map(TasCorporatePerformanceRow::geographyName))
				.anyMatch(name -> name.equals("HOBART") || name.equals("SOUTH") || name.equals("STATE"));
	}

	@Test
	void extractsReportPeriodFromHeader() throws Exception {
		Path pdf = new ClassPathResource("tas/corporate-performance-report-sample.pdf").getFile().toPath();
		String text = TasPdfTextExtractor.extractFullText(pdf);
		assertThat(TasCorporatePerformancePdfParser.parseReportPeriod(text, pdf)).isEqualTo("Mar 2026");
	}

}
