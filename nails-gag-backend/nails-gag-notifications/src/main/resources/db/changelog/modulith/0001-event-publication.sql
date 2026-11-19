--liquibase formatted sql

--changeset nails-gag:modulith-0001-schema
CREATE SCHEMA IF NOT EXISTS modulith;

--changeset nails-gag:modulith-0001-event-publication
--comment: реестр публикаций Spring Modulith 2.1.1
-- Дословно schemas/v2/schema-postgresql.sql из spring-modulith-events-jdbc 2.1.1, к именам таблицы добавлена схема
-- modulith (spring.modulith.events.jdbc.schema), а длинная строка первого индекса перенесена (только пробелы).
-- Сам Spring Modulith таблиц не создаёт (schema-initialization выключено).
CREATE TABLE IF NOT EXISTS modulith.event_publication
(
  id                     UUID NOT NULL,
  listener_id            TEXT NOT NULL,
  event_type             TEXT NOT NULL,
  serialized_event       TEXT NOT NULL,
  publication_date       TIMESTAMP WITH TIME ZONE NOT NULL,
  completion_date        TIMESTAMP WITH TIME ZONE,
  status                 TEXT,
  completion_attempts    INT,
  last_resubmission_date TIMESTAMP WITH TIME ZONE,
  PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS event_publication_serialized_event_hash_idx
  ON modulith.event_publication USING hash(serialized_event);
CREATE INDEX IF NOT EXISTS event_publication_by_completion_date_idx ON modulith.event_publication (completion_date);
