package com.medibook.integration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.OpenAPIV3Parser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("OpenAPI Contract Integration Test")
@TestPropertySource(properties = {
        "app.api-docs.enabled=true",
        "springdoc.api-docs.enabled=true",
        "springdoc.swagger-ui.enabled=true"
})
class OpenApiContractIntegrationTest extends IntegrationTestSupport {

    @Autowired
    MockMvc mockMvc;

    @Test
    @DisplayName("/api-docs publishes a valid OpenAPI document with core paths present")
    void apiDocs_areValidAndContainCorePaths() throws Exception {
        String body = mockMvc.perform(get("/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        ParseOptions parseOptions = new ParseOptions();
        parseOptions.setResolve(true);
        SwaggerParseResult parseResult = new OpenAPIV3Parser().readContents(body, null, parseOptions);

        assertThat(parseResult.getMessages()).isEmpty();
        OpenAPI openAPI = parseResult.getOpenAPI();
        assertThat(openAPI).isNotNull();
        assertThat(openAPI.getPaths()).containsKeys(
                "/api/v1/auth/login",
                "/api/v1/appointments",
                "/api/v1/me/appointments",
                "/api/v1/doctors/search");
    }
}
