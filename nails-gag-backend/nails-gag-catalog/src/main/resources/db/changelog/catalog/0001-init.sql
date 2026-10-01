--liquibase formatted sql

--changeset nails-gag:catalog-0001-schema
CREATE SCHEMA IF NOT EXISTS catalog;

--changeset nails-gag:catalog-0001-service
--comment: услуги и дополнения; записи хранят снимок названия и цены, поэтому услуги не удаляются, а скрываются
CREATE TABLE catalog.service (
    id                   uuid PRIMARY KEY,
    kind                 varchar(16)    NOT NULL CONSTRAINT service_kind_check CHECK (kind IN ('MAIN', 'ADDON')),
    title                varchar(200)   NOT NULL,
    description          text           NOT NULL DEFAULT '',
    price                numeric(12, 2) NOT NULL CONSTRAINT service_price_check CHECK (price >= 0),
    duration_minutes     integer        CONSTRAINT service_duration_minutes_check CHECK (duration_minutes > 0),
    category             varchar(100)   NOT NULL,
    sort_order           integer        NOT NULL DEFAULT 0,
    show_on_landing      boolean        NOT NULL DEFAULT false,
    highlight_on_landing boolean        NOT NULL DEFAULT false,
    active               boolean        NOT NULL DEFAULT true,
    created_at           timestamptz    NOT NULL,
    updated_at           timestamptz    NOT NULL
);

CREATE INDEX service_active_sort_idx ON catalog.service (active, sort_order);
