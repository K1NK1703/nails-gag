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
 * Тест модуля финансов — как {@link ModuleTest}, но с режимом {@code ALL_DEPENDENCIES}: отчёты читают денежные факты
 * модуля записей, а он сам не работает без расписания, каталога, клиентов и файлов — прямых зависимостей финансам
 * мало. Тесты отчётов готовят визиты через публичные API этих модулей.
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
