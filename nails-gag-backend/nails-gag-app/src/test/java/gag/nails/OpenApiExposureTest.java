package gag.nails;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.util.ClassUtils;
import org.springframework.web.client.RestClient;

/**
 * springdoc подключён как {@code developmentOnly} (спецификация API, §5.5): он есть только при {@code bootRun}, а на
 * рабочем classpath — в {@code app.jar} и образе — его нет, и схема API наружу не видна. Адреса заданы, как в профиле
 * {@code local}: даже с ними схемы нет.
 */
@SpringBootTest(
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {"springdoc.api-docs.path=/api/openapi.json", "springdoc.swagger-ui.path=/api/docs"})
@Import(PostgresTestcontainers.class)
class OpenApiExposureTest {

    @LocalServerPort
    int port;

    @Test
    void springdocIsNotOnRuntimeClasspath() {
        assertThat(ClassUtils.isPresent(
                        "org.springdoc.core.properties.SpringDocConfigProperties",
                        getClass().getClassLoader()))
                .isFalse();
    }

    @Test
    void openApiAndSwaggerUiAreNotFound() {
        var client = RestClient.create("http://localhost:" + port);

        for (var path : new String[] {"/api/openapi.json", "/api/docs", "/v3/api-docs", "/swagger-ui.html"}) {
            var status = client.get().uri(path).exchange((request, response) -> response.getStatusCode());
            assertThat(status).as(path).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }
}
