package gag.nails.notifications.internal.listen;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.scheduling.annotation.Async;

/**
 * Слушатель событий модуля — {@link ApplicationModuleListener} (после фиксации, в своей транзакции, через реестр
 * публикаций) на своём пуле {@value ListenerExecutorConfiguration#EXECUTOR}. Своё {@link Async} ближе к методу, чем
 * {@code @Async} без имени в {@link ApplicationModuleListener}, поэтому в объединённой аннотации действует оно.
 */
@ApplicationModuleListener
@Async(ListenerExecutorConfiguration.EXECUTOR)
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@interface NotificationsListener {}
