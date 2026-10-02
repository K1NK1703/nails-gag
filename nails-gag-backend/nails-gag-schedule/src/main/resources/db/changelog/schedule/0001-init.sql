--liquibase formatted sql

--changeset nails-gag:schedule-0001-btree-gist
--comment: ограничение-исключение по интервалам слотов требует btree_gist; расширение создаёт модуль, которому оно нужно
CREATE EXTENSION IF NOT EXISTS btree_gist;

--changeset nails-gag:schedule-0001-schema
CREATE SCHEMA IF NOT EXISTS schedule;

--changeset nails-gag:schedule-0001-settings
--comment: одна строка настроек: singleton не даёт завести вторую; каждое изменение — строка journal'а settings_change
CREATE TABLE schedule.settings (
    id                    uuid        PRIMARY KEY,
    singleton             boolean     NOT NULL DEFAULT true
                                      CONSTRAINT settings_singleton_check CHECK (singleton)
                                      CONSTRAINT settings_singleton_key UNIQUE,
    slot_duration_minutes integer     NOT NULL CONSTRAINT settings_slot_duration_minutes_check CHECK (slot_duration_minutes > 0),
    updated_at            timestamptz NOT NULL
);

CREATE TABLE schedule.settings_change (
    id         uuid        PRIMARY KEY,
    field      varchar(64) NOT NULL,
    old_value  varchar(64) NOT NULL,
    new_value  varchar(64) NOT NULL,
    changed_at timestamptz NOT NULL
);

CREATE INDEX settings_change_changed_at_idx ON schedule.settings_change (changed_at DESC);

--changeset nails-gag:schedule-0001-slot
--comment: существующий слот = открытый для записи, закрыть слот = удалить; интервалы слотов не пересекаются
CREATE TABLE schedule.slot (
    id               uuid        PRIMARY KEY,
    day              date        NOT NULL,
    starts_at        timestamptz NOT NULL,
    ends_at          timestamptz NOT NULL,
    duration_minutes integer     NOT NULL CONSTRAINT slot_duration_minutes_check CHECK (duration_minutes > 0),
    occupied         boolean     NOT NULL DEFAULT false,
    booking_id       uuid,
    created_at       timestamptz NOT NULL,
    CONSTRAINT slot_period_check CHECK (ends_at > starts_at),
    CONSTRAINT slot_occupied_booking_check CHECK (occupied = (booking_id IS NOT NULL)),
    CONSTRAINT slot_no_overlap EXCLUDE USING gist (tstzrange(starts_at, ends_at) WITH &&)
);

CREATE UNIQUE INDEX slot_booking_id_key ON schedule.slot (booking_id) WHERE booking_id IS NOT NULL;
CREATE INDEX slot_day_idx ON schedule.slot (day);
CREATE INDEX slot_starts_at_idx ON schedule.slot (starts_at);

--changeset nails-gag:schedule-0001-day-template
--comment: шаблон дня хранит только времена начала; длительность берётся из настройки в момент применения
CREATE TABLE schedule.day_template (
    id         uuid         PRIMARY KEY,
    name       varchar(100) NOT NULL CONSTRAINT day_template_name_key UNIQUE,
    is_default boolean      NOT NULL DEFAULT false,
    sort_order integer      NOT NULL DEFAULT 0,
    created_at timestamptz  NOT NULL,
    updated_at timestamptz  NOT NULL
);

CREATE UNIQUE INDEX day_template_default_key ON schedule.day_template (is_default) WHERE is_default;

CREATE TABLE schedule.day_template_item (
    id          uuid PRIMARY KEY,
    template_id uuid NOT NULL CONSTRAINT day_template_item_template_id_fkey REFERENCES schedule.day_template (id) ON DELETE CASCADE,
    start_time  time NOT NULL,
    CONSTRAINT day_template_item_template_id_start_time_key UNIQUE (template_id, start_time)
);

--changeset nails-gag:schedule-0001-week-template
--comment: шаблон недели: рабочий день недели → шаблон дня; дня нет в таблице — выходной
CREATE TABLE schedule.week_template (
    id         uuid         PRIMARY KEY,
    name       varchar(100) NOT NULL CONSTRAINT week_template_name_key UNIQUE,
    created_at timestamptz  NOT NULL,
    updated_at timestamptz  NOT NULL
);

CREATE TABLE schedule.week_template_day (
    id              uuid    PRIMARY KEY,
    template_id     uuid    NOT NULL CONSTRAINT week_template_day_template_id_fkey REFERENCES schedule.week_template (id) ON DELETE CASCADE,
    weekday         integer NOT NULL CONSTRAINT week_template_day_weekday_check CHECK (weekday BETWEEN 1 AND 7),
    day_template_id uuid    NOT NULL CONSTRAINT week_template_day_day_template_id_fkey REFERENCES schedule.day_template (id),
    CONSTRAINT week_template_day_template_id_weekday_key UNIQUE (template_id, weekday)
);

--changeset nails-gag:schedule-0001-rotation
--comment: одна строка: якорная неделя и упорядоченный список шаблонов недель
CREATE TABLE schedule.rotation (
    id            uuid        PRIMARY KEY,
    singleton     boolean     NOT NULL DEFAULT true
                              CONSTRAINT rotation_singleton_check CHECK (singleton)
                              CONSTRAINT rotation_singleton_key UNIQUE,
    anchor_monday date        NOT NULL CONSTRAINT rotation_anchor_monday_check CHECK (extract(isodow FROM anchor_monday) = 1),
    anchor_index  integer     NOT NULL CONSTRAINT rotation_anchor_index_check CHECK (anchor_index >= 0),
    updated_at    timestamptz NOT NULL
);

CREATE TABLE schedule.rotation_week (
    id               uuid    PRIMARY KEY,
    rotation_id      uuid    NOT NULL CONSTRAINT rotation_week_rotation_id_fkey REFERENCES schedule.rotation (id) ON DELETE CASCADE,
    ordinal          integer NOT NULL CONSTRAINT rotation_week_ordinal_check CHECK (ordinal >= 0),
    week_template_id uuid    NOT NULL CONSTRAINT rotation_week_week_template_id_fkey REFERENCES schedule.week_template (id)
);

-- индекс не уникальный намеренно: ротация переписывается целиком (удаление старых строк и вставка новых в одном flush)
CREATE INDEX rotation_week_rotation_id_idx ON schedule.rotation_week (rotation_id, ordinal);
