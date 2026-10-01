package gag.nails.app.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * Отдаёт записи Logback в OpenTelemetry: Spring Boot настраивает экспорт логов по OTLP, но сам
 * Logback с ним не связывает. Appender добавляется в код, а не в {@code logback-spring.xml}, чтобы
 * не терять штатную настройку консоли Boot (переключение JSON/текст через
 * {@code logging.structured.format.console}). Без адреса экспорта записи никуда не уходят.
 */
@Component
class OpenTelemetryLogbackBridge implements InitializingBean, DisposableBean {

    private static final String APPENDER_NAME = "OTEL";

    private final OpenTelemetry openTelemetry;
    private final OpenTelemetryAppender appender = new OpenTelemetryAppender();

    OpenTelemetryLogbackBridge(OpenTelemetry openTelemetry) {
        this.openTelemetry = openTelemetry;
    }

    @Override
    public void afterPropertiesSet() {
        Logger root = rootLogger();
        // Повторный контекст в тестах не должен множить appender-ы.
        Appender<ILoggingEvent> previous = root.getAppender(APPENDER_NAME);
        if (previous != null) {
            root.detachAppender(previous);
            previous.stop();
        }
        appender.setName(APPENDER_NAME);
        appender.setContext(root.getLoggerContext());
        appender.setOpenTelemetry(openTelemetry);
        appender.start();
        root.addAppender(appender);
    }

    @Override
    public void destroy() {
        rootLogger().detachAppender(appender);
        appender.stop();
    }

    private static Logger rootLogger() {
        return (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    }
}
