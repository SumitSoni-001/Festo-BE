package com.festo.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI festoOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("Festo API")
                .description("REST API for the Festo college event booking platform")
                .version("v1.0"));
    }
}
