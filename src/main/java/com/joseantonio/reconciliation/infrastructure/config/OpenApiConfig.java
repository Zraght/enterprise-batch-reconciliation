package com.joseantonio.reconciliation.infrastructure.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    static final String API_KEY_SCHEME = "ApiKeyAuth";

    @Bean
    public OpenAPI reconciliationOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Enterprise Batch Reconciliation & Audit Engine")
                        .version("1.1.0")
                        .description("Corporate bank reconciliation and transactional audit engine: upload external "
                                + "settlement files, reconcile them against the internal ledger asynchronously, audit the "
                                + "transactions and query discrepancies. When an API key is configured, send it in the "
                                + "X-API-Key header (use the Authorize button)."))
                .components(new Components().addSecuritySchemes(API_KEY_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.HEADER)
                        .name("X-API-Key")))
                .addSecurityItem(new SecurityRequirement().addList(API_KEY_SCHEME));
    }
}
