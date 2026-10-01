package gag.nails;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;

/**
 * Корень приложения для тестов доменных модулей. Лежит в {@code gag.nails}, чтобы Spring Modulith
 * считал модулями пакеты {@code gag.nails.*}, оказавшиеся на тестовом classpath модуля.
 *
 * <p>Общие модули поднимаются в каждом тесте модуля независимо от его зависимостей: {@code common}
 * — потому что {@code @ApplicationModuleTest} подтягивает только модули, чьи бины внедряются в бины
 * тестируемого модуля, а часы и {@code SalonTimeZone} нужны тестовой обвязке всегда;
 * {@code testsupport} — потому что иначе бины импортированных из него конфигураций (контейнер
 * PostgreSQL, подставные часы) выбрасываются как принадлежащие чужому модулю.
 *
 * <p>В {@code nails-gag-app} не используется: у него свой класс приложения.
 */
@SpringBootApplication
@Modulithic(sharedModules = {"common", "testsupport"})
public class TestApplication {}
