--liquibase formatted sql

--changeset nails-gag:notifications-0001-schema
CREATE SCHEMA IF NOT EXISTS notifications;

--changeset nails-gag:notifications-0001-settings
--comment: одна строка настроек напоминаний: singleton не даёт завести вторую; NULL — напоминание выключено
CREATE TABLE notifications.settings (
    id                     uuid        PRIMARY KEY,
    singleton              boolean     NOT NULL DEFAULT true
                                       CONSTRAINT settings_singleton_check CHECK (singleton)
                                       CONSTRAINT settings_singleton_key UNIQUE,
    -- «Накануне в ЧЧ:ММ» по времени салона.
    reminder_day_before_at time,
    -- «За N часов» до начала визита.
    reminder_hours_before  integer     CONSTRAINT settings_reminder_hours_before_check
                                       CHECK (reminder_hours_before BETWEEN 1 AND 24),
    updated_at             timestamptz NOT NULL
);

-- Журнал изменений настроек напоминаний: поле — имя колонки, значения — текстом, NULL — выключено.
CREATE TABLE notifications.settings_change (
    id         uuid        PRIMARY KEY,
    field      varchar(64) NOT NULL,
    old_value  varchar(64),
    new_value  varchar(64),
    -- Аккаунт сотрудника; пусто у внутренних акторов.
    changed_by uuid,
    changed_at timestamptz NOT NULL
);

CREATE INDEX settings_change_changed_at_idx ON notifications.settings_change (changed_at DESC);

--changeset nails-gag:notifications-0001-settings-seed
--comment: напоминания по умолчанию: накануне в 12:00 и за 2 часа
INSERT INTO notifications.settings (id, reminder_day_before_at, reminder_hours_before, updated_at)
VALUES ('019a8600-0000-7000-8000-000000000001', '12:00', 2, now());

--changeset nails-gag:notifications-0001-preference
--comment: каналы аккаунта; нет строки — умолчания по роли; account_id — из схемы identity, без внешнего ключа
CREATE TABLE notifications.preference (
    id               uuid        PRIMARY KEY,
    account_id       uuid        NOT NULL CONSTRAINT preference_account_id_key UNIQUE,
    email_enabled    boolean     NOT NULL,
    telegram_enabled boolean     NOT NULL,
    updated_at       timestamptz NOT NULL
);

--changeset nails-gag:notifications-0001-telegram-chat
--comment: привязка Telegram-бота: у аккаунта один чат, чат — у одного аккаунта
CREATE TABLE notifications.telegram_chat (
    id         uuid        PRIMARY KEY,
    account_id uuid        NOT NULL CONSTRAINT telegram_chat_account_id_key UNIQUE,
    chat_id    bigint      NOT NULL CONSTRAINT telegram_chat_chat_id_key UNIQUE,
    linked_at  timestamptz NOT NULL
);

--changeset nails-gag:notifications-0001-link-token
--comment: одноразовая ссылка привязки бота: хранится SHA-256 токена, новый токен аккаунта заменяет прежний
CREATE TABLE notifications.link_token (
    id         uuid        PRIMARY KEY,
    account_id uuid        NOT NULL CONSTRAINT link_token_account_id_key UNIQUE,
    token_hash bytea       NOT NULL CONSTRAINT link_token_token_hash_key UNIQUE
                                    CONSTRAINT link_token_token_hash_check CHECK (octet_length(token_hash) = 32),
    expires_at timestamptz NOT NULL
);

--changeset nails-gag:notifications-0001-message
--comment: логическое сообщение — кому, о чём и когда; без текста и адресов: текст собирается при отправке
CREATE TABLE notifications.message (
    id            uuid         PRIMARY KEY,
    -- Получатель — ровно одно из двух: карточка клиента (клиентские сообщения) или аккаунт (мастерские).
    -- Идентификаторы из схем clients и identity, без внешних ключей между схемами.
    client_id     uuid,
    account_id    uuid,
    kind          varchar(32)  NOT NULL CONSTRAINT message_kind_check CHECK (kind IN (
                      'BOOKING_RECEIVED', 'BOOKED_BY_MASTER', 'PREPAYMENT_REQUESTED', 'CONFIRMED',
                      'CANCELLED_BY_CLIENT', 'CANCELLED', 'RESCHEDULED', 'EARLIER_PROPOSED',
                      'REMINDER_DAY_BEFORE', 'REMINDER_HOURS_BEFORE',
                      'STAFF_NEW_BOOKING', 'STAFF_CLIENT_CANCELLED', 'STAFF_PREPAYMENT_TIMEOUT',
                      'STAFF_CLIENT_RESCHEDULED', 'STAFF_EARLIER_ACCEPTED', 'STAFF_EARLIER_DECLINED')),
    booking_id    uuid         NOT NULL,
    due_at        timestamptz  NOT NULL,
    expires_at    timestamptz  NOT NULL,
    status        varchar(16)  NOT NULL CONSTRAINT message_status_check
                                        CHECK (status IN ('PENDING', 'DISPATCHED', 'CANCELLED')),
    -- Повтор события после рестарта не создаёт второе сообщение: вставка ON CONFLICT (dedup_key) DO NOTHING.
    dedup_key     varchar(255) NOT NULL CONSTRAINT message_dedup_key_key UNIQUE,
    -- Момент события, из-за которого сообщение поставлено (у напоминания — момент расчёта): при равном due_at
    -- сообщения уходят в порядке событий, а не постановки — слушатели событий асинхронны и идут не по порядку.
    -- Очередь пишет его всегда; умолчание — для строк, вставленных мимо очереди (ручные правки).
    occurred_at   timestamptz  NOT NULL DEFAULT now(),
    created_at    timestamptz  NOT NULL,
    dispatched_at timestamptz,
    CONSTRAINT message_recipient_check CHECK ((client_id IS NULL) <> (account_id IS NULL)),
    -- Мастерские виды (STAFF_*) — аккаунту, остальные — карточке клиента.
    CONSTRAINT message_kind_recipient_check CHECK (starts_with(kind, 'STAFF_') = (account_id IS NOT NULL)),
    CONSTRAINT message_expires_at_check CHECK (expires_at > due_at),
    CONSTRAINT message_dispatched_at_check CHECK ((status = 'DISPATCHED') = (dispatched_at IS NOT NULL))
);

-- Созревшие сообщения для отправки в порядке due_at, момента события и id.
CREATE INDEX message_pending_due_at_idx ON notifications.message (due_at, occurred_at, id) WHERE status = 'PENDING';
-- Перепланирование и отмена напоминаний записи, журнал доставок по записи.
CREATE INDEX message_booking_id_idx ON notifications.message (booking_id);
-- Обезличивание и объединение карточек трогают только неотправленные сообщения.
CREATE INDEX message_client_id_idx ON notifications.message (client_id) WHERE status = 'PENDING';
-- Удаление аккаунта: отмена его неотправленных мастерских сообщений.
CREATE INDEX message_account_id_idx ON notifications.message (account_id) WHERE status = 'PENDING';

--changeset nails-gag:notifications-0001-delivery
--comment: доставка сообщения по одному каналу с повторами; адрес и chat_id не хранятся
CREATE TABLE notifications.delivery (
    id              uuid        PRIMARY KEY,
    -- Внутри схемы: уборка удаляет сообщение вместе с доставками.
    message_id      uuid        NOT NULL CONSTRAINT delivery_message_id_fkey
                                REFERENCES notifications.message (id) ON DELETE CASCADE,
    channel         varchar(16) NOT NULL CONSTRAINT delivery_channel_check CHECK (channel IN ('EMAIL', 'TELEGRAM')),
    status          varchar(16) NOT NULL CONSTRAINT delivery_status_check
                                CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    attempts        integer     NOT NULL CONSTRAINT delivery_attempts_check CHECK (attempts >= 0),
    -- У PENDING — когда пробовать (или конец аренды отправки); у завершённых — последнее значение.
    next_attempt_at timestamptz NOT NULL,
    sent_at         timestamptz,
    -- Код последней ошибки (expired, telegram_blocked…); набор задают каналы, поэтому без перечня — только форма.
    error_code      varchar(32) CONSTRAINT delivery_error_code_check CHECK (error_code ~ '^[a-z][a-z0-9_]*$'),
    CONSTRAINT delivery_message_id_channel_key UNIQUE (message_id, channel),
    CONSTRAINT delivery_sent_at_check CHECK ((status = 'SENT') = (sent_at IS NOT NULL)),
    -- Неудача всегда объясняется кодом.
    CONSTRAINT delivery_error_code_failed_check CHECK (status <> 'FAILED' OR error_code IS NOT NULL)
);

-- Созревшие доставки для отправки.
CREATE INDEX delivery_pending_next_attempt_at_idx ON notifications.delivery (next_attempt_at)
    WHERE status = 'PENDING';
