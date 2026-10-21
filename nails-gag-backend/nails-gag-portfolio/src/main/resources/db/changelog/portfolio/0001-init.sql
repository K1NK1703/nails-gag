--liquibase formatted sql

--changeset nails-gag:portfolio-0001-schema
CREATE SCHEMA IF NOT EXISTS portfolio;

--changeset nails-gag:portfolio-0001-work
--comment: работа в портфолио; фото — файл модуля files назначения PORTFOLIO, варианты картинки отдаёт он
CREATE TABLE portfolio.work (
    id         uuid        PRIMARY KEY,
    -- files.stored_file; внешних ключей между схемами нет
    file_id    uuid        NOT NULL CONSTRAINT work_file_id_key UNIQUE,
    -- копия размеров файла: они не меняются
    width      integer     NOT NULL CONSTRAINT work_width_check CHECK (width > 0),
    height     integer     NOT NULL CONSTRAINT work_height_check CHECK (height > 0),
    alt        text        NOT NULL CONSTRAINT work_alt_check CHECK (char_length(alt) BETWEEN 1 AND 200),
    caption    text        CONSTRAINT work_caption_check CHECK (char_length(caption) BETWEEN 1 AND 200),
    position   integer     NOT NULL,
    visible    boolean     NOT NULL,
    source     text        NOT NULL CONSTRAINT work_source_check
                               CHECK (source IN ('CLIENT', 'CLIENT_OUTSIDE_SITE', 'EXAMPLE')),
    booking_id uuid,                   -- booking.booking; только у работы клиента
    client_id  uuid,                   -- clients.client, клиент записи; только у работы клиента
    created_at timestamptz NOT NULL,
    CONSTRAINT work_client_booking_check CHECK ((source = 'CLIENT') = (booking_id IS NOT NULL)),
    CONSTRAINT work_client_client_check CHECK ((source = 'CLIENT') = (client_id IS NOT NULL)),
    -- Отложенная: перестановка меняет позиции нескольких строк, повтор допустим только посреди транзакции.
    CONSTRAINT work_position_key UNIQUE (position) DEFERRABLE INITIALLY DEFERRED
);

-- Лендинг: видимые работы по порядку.
CREATE INDEX work_visible_position_idx ON portfolio.work (visible, position);

-- Работы клиента: публичность по согласию, объединение и обезличивание карточки.
CREATE INDEX work_client_id_idx ON portfolio.work (client_id) WHERE client_id IS NOT NULL;

--changeset nails-gag:portfolio-0001-publication-consent
--comment: клиенты с действующим согласием на публикацию фото — копия согласий модуля clients по его событиям
CREATE TABLE portfolio.publication_consent (
    client_id uuid        PRIMARY KEY, -- clients.client
    given_at  timestamptz NOT NULL      -- когда согласие впервые попало в копию
);
