package gag.nails.common.event;

import java.time.Instant;

/** Событие предметной области, которое модуль публикует наружу через {@code ApplicationEventPublisher}. */
public interface DomainEvent {

    Instant occurredAt();
}
