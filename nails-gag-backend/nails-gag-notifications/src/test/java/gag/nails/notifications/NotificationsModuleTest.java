package gag.nails.notifications;

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
 * Тест модуля уведомлений — как {@link ModuleTest}, но с режимом {@code ALL_DEPENDENCIES}, с модулем записей и без
 * тестовой транзакции с откатом. Модуль записей и то, без чего он не работает (расписание, каталог, файлы), включены
 * явно: уведомления только слушают его события, а такую зависимость Spring Modulith в тест не поднимает, — тесты же
 * создают, отменяют и переносят настоящие записи.
 * Транзакция: слушатели событий ({@code @ApplicationModuleListener}) срабатывают только после фиксации, поэтому
 * класс, которому фиксация не нужна, сам ставит {@code @Transactional}, а фиксирующие тесты работают на своих записях
 * и убирают их за собой.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ApplicationModuleTest(
        mode = BootstrapMode.ALL_DEPENDENCIES,
        extraIncludes = {"booking", "schedule", "catalog", "files"})
@ActiveProfiles("test")
@Import({PostgresTestcontainers.class, TestTimeConfiguration.class})
@TestExecutionListeners(listeners = ResetClockListener.class, mergeMode = MergeMode.MERGE_WITH_DEFAULTS)
public @interface NotificationsModuleTest {}
