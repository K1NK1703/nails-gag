package gag.nails.finance;

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
import org.springframework.transaction.annotation.Transactional;

/**
 * Как {@link ModuleTest}, но со всеми зависимостями модуля, а не только прямыми: отчёты финансов читают модуль
 * записей, а он сам не работает без расписания, каталога и клиентов.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ApplicationModuleTest(mode = BootstrapMode.ALL_DEPENDENCIES)
@ActiveProfiles("test")
@Import({PostgresTestcontainers.class, TestTimeConfiguration.class})
@TestExecutionListeners(listeners = ResetClockListener.class, mergeMode = MergeMode.MERGE_WITH_DEFAULTS)
@Transactional
@interface FinanceModuleTest {}
