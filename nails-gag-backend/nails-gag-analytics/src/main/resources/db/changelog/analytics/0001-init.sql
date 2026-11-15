--liquibase formatted sql

--changeset nails-gag:analytics-0001-schema
CREATE SCHEMA IF NOT EXISTS analytics;

--changeset nails-gag:analytics-0001-daily-count
--comment: дневные счётчики событий сайта; сырые события не хранятся — событие сразу прибавляется к счётчику дня
CREATE TABLE analytics.daily_count (
    -- День в поясе салона.
    day    date        NOT NULL,
    metric varchar(16) NOT NULL CONSTRAINT daily_count_metric_check CHECK (metric IN (
               'page_view', 'cta_click', 'contact_click', 'gallery_open', 'faq_open', 'map_open',
               'source', 'device', 'visitors')),
    -- Путь, место кнопки, номер фото, домен источника… За день у метрики около max-keys-per-metric ключей плюс
    -- other: предел мягкий, гонка новых ключей может его немного превысить.
    key    text        NOT NULL CONSTRAINT daily_count_key_check CHECK (char_length(key) BETWEEN 1 AND 64),
    count  bigint      NOT NULL CONSTRAINT daily_count_count_check CHECK (count > 0),
    CONSTRAINT daily_count_pkey PRIMARY KEY (day, metric, key)
);

--changeset nails-gag:analytics-0001-day-salt
--comment: соль суточного отпечатка посетителя; живёт до конца дня, потом стирается уборкой
CREATE TABLE analytics.day_salt (
    day  date  CONSTRAINT day_salt_pkey PRIMARY KEY,
    salt bytea NOT NULL CONSTRAINT day_salt_salt_check CHECK (octet_length(salt) = 32)
);

--changeset nails-gag:analytics-0001-visitor
--comment: суточные отпечатки посетителей (SHA-256 соли дня, IP и User-Agent); IP и User-Agent не хранятся
CREATE TABLE analytics.visitor (
    day  date  NOT NULL,
    hash bytea NOT NULL CONSTRAINT visitor_hash_check CHECK (octet_length(hash) = 32),
    CONSTRAINT visitor_pkey PRIMARY KEY (day, hash)
);
