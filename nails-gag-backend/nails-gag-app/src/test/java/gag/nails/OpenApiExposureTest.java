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
 * рабочем classpath — в {@code app.jar} и образе — его нет, и схема API наружу не видна.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
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

        for (var path : new String[] {"/api/openapi.json", "/api/docs"}) {
            var status = client.get().uri(path).exchange((request, response) -> response.getStatusCode());
            assertThat(status).as(path).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }
}
