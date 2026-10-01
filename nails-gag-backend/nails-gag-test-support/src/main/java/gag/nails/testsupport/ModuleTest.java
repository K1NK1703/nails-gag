package gag.nails.testsupport;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Тест одного модуля с его прямыми зависимостями (например, {@code common} с его {@code Clock}),
 * настоящим PostgreSQL в Testcontainers, миграциями Liquibase и подставными часами. Каждый тест
 * идёт в транзакции с откатом, поэтому тесты не видят данных друг друга. Транзакция не
 * фиксируется: ограничения БД срабатывают только при flush (JPQL-запрос или явный {@code
 * flush()}), а слушатели {@code AFTER_COMMIT} не вызываются — события проверяются через {@code
 * PublishedEvents}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ApplicationModuleTest(mode = BootstrapMode.DIRECT_DEPENDENCIES)
@ActiveProfiles("test")
@Import({PostgresTestcontainers.class, TestTimeConfiguration.class})
@Transactional
public @interface ModuleTest {}
