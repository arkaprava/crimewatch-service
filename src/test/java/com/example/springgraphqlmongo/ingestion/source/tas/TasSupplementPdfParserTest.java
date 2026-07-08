package com.example.springgraphqlmongo.ingestion.source.tas;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TasSupplementPdfParserTest {

	@Test
	void parsesMajorCategoriesFromSamplePdf() throws Exception {
		Path pdf = new ClassPathResource("tas/crime-statistics-supplement-sample.pdf").getFile().toPath();
		var rows = TasSupplementPdfParser.parse(pdf);
		assertThat(rows).isNotEmpty();
		assertThat(rows.getFirst().financialYear()).matches("\\d{4}-\\d{2}");
		assertThat(rows.stream().map(TasSupplementRow::category)).anyMatch(c -> c.contains("Offences Against"));
		assertThat(rows.stream().mapToInt(TasSupplementRow::recorded).sum()).isGreaterThan(1000);
	}

	@Test
	void extractsFinancialYearFromTitle() throws Exception {
		Path pdf = new ClassPathResource("tas/crime-statistics-supplement-sample.pdf").getFile().toPath();
		String text = TasPdfTextExtractor.extractFullText(pdf);
		assertThat(TasSupplementPdfParser.parseFinancialYear(text, pdf)).isEqualTo("2024-25");
	}

}
