package gag.nails.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
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
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
        // Повторный контекст в тестах не должен множить appender-ы.
        root.detachAppender(APPENDER_NAME);
        appender.setName(APPENDER_NAME);
        appender.setContext(context);
        appender.setOpenTelemetry(openTelemetry);
        appender.start();
        root.addAppender(appender);
    }

    @Override
    public void destroy() {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        root.detachAppender(appender);
        appender.stop();
    }
}
