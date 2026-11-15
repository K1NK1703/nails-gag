package gag.nails.analytics;

import gag.nails.testsupport.ModuleTest;
import gag.nails.testsupport.PostgresTestcontainers;
import gag.nails.testsupport.ResetClockListener;
import gag.nails.testsupport.TestTimeConfiguration;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.TestExecutionListeners.MergeMode;

/**
 * Тест модуля счётчика — как {@link ModuleTest}, но без тестовой транзакции с откатом: приём пишет в своей
 * транзакции ({@code REQUIRES_NEW}), и откат тестовой её бы не отменил, а уборка внутри откатываемой транзакции сама
 * откатилась бы. Поэтому тесты работают на настоящих транзакциях, на своих далёких днях, и убирают их за собой
 * ({@link AnalyticsTestSupport}).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ApplicationModuleTest(mode = BootstrapMode.DIRECT_DEPENDENCIES)
@ActiveProfiles("test")
@Import({PostgresTestcontainers.class, TestTimeConfiguration.class})
@TestExecutionListeners(listeners = ResetClockListener.class, mergeMode = MergeMode.MERGE_WITH_DEFAULTS)
public @interface AnalyticsModuleTest {}
