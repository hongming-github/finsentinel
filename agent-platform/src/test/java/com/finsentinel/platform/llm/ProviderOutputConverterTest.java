package com.finsentinel.platform.llm;

import com.finsentinel.platform.investigation.CaseReport;
import org.junit.jupiter.api.Test;
import org.springframework.ai.converter.BeanOutputConverter;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderOutputConverterTest {

    @Test
    void groqGetsASchemaWithoutNumericFormats() {
        ProviderOutputConverter<CaseReport> groq = ProviderOutputConverter.of(CaseReport.class, TestLlm.GROQ);

        assertThat(new BeanOutputConverter<>(CaseReport.class).getJsonSchema()).as("what Spring AI generates")
                .contains("\"format\" : \"int32\"");
        assertThat(groq.getJsonSchema()).doesNotContain("\"format\"").contains("\"riskScore\"", "\"integer\"");
    }

    @Test
    void openAiRequestsAreUnchanged() {
        ProviderOutputConverter<CaseReport> openAi = ProviderOutputConverter.of(CaseReport.class, TestLlm.OPENAI);
        BeanOutputConverter<CaseReport> plain = new BeanOutputConverter<>(CaseReport.class);

        assertThat(openAi.getJsonSchema()).isEqualTo(plain.getJsonSchema());
        assertThat(openAi.getFormat()).isEqualTo(plain.getFormat());
    }
}
