package gag.nails.api;

import gag.nails.testsupport.PostgresTestcontainers;
import gag.nails.testsupport.ResetClockListener;
import gag.nails.testsupport.TestTimeConfiguration;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.TestExecutionListeners.MergeMode;

/**
 * Сквозной HTTP-тест слоя {@code api}: всё приложение на случайном порту, настоящий PostgreSQL в Testcontainers,
 * подставные часы и подставной провайдер входа. Запросы идут через {@code RestTestClient} по сети, поэтому
 * транзакции фиксируются по-настоящему и данные между тестами не откатываются: каждый тест заводит своих людей
 * ({@link ApiTestSupport#uniqueSubject()}, {@link ApiTestSupport#uniquePhone()}) и не опирается на пустую базу.
 * Лимиты частоты подняты, чтобы тесты не упирались в них; {@code api-developer} — разработчик на весь прогон.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "nails-gag.identity.bootstrap-developers=FAKE:api-developer",
            "nails-gag.api.site-origin=http://localhost",
            "nails-gag.api.rate-limits.auth-per-minute=100000",
            "nails-gag.api.rate-limits.anonymous-per-minute=100000",
            "nails-gag.api.rate-limits.authenticated-per-minute=100000"
        })
@AutoConfigureRestTestClient
@ActiveProfiles("test")
@Import({PostgresTestcontainers.class, TestTimeConfiguration.class})
@TestExecutionListeners(listeners = ResetClockListener.class, mergeMode = MergeMode.MERGE_WITH_DEFAULTS)
public @interface ApiTest {}
