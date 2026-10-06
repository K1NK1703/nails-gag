--liquibase formatted sql

--changeset nails-gag:identity-0001-schema
CREATE SCHEMA IF NOT EXISTS identity;

--changeset nails-gag:identity-0001-account
--comment: человек в системе; карточка клиента ссылается на него через clients.client.account_id
CREATE TABLE identity.account (
    id            uuid        PRIMARY KEY,
    created_at    timestamptz NOT NULL,
    last_login_at timestamptz NOT NULL
);

--changeset nails-gag:identity-0001-login-method
--comment: способ входа у провайдера; телефон и почта — только подтверждённые провайдером; FAKE — подставной провайдер тестов
CREATE TABLE identity.login_method (
    id           uuid         PRIMARY KEY,
    account_id   uuid         NOT NULL CONSTRAINT login_method_account_id_fkey REFERENCES identity.account (id) ON DELETE CASCADE,
    provider     varchar(16)  NOT NULL CONSTRAINT login_method_provider_check CHECK (provider IN ('YANDEX', 'VK', 'FAKE')),
    subject      varchar(128) NOT NULL,
    phone        varchar(16),
    email        varchar(254),
    display_name varchar(200),
    avatar_url   varchar(1024),
    linked_at    timestamptz  NOT NULL,
    last_used_at timestamptz  NOT NULL,
    CONSTRAINT login_method_provider_subject_key UNIQUE (provider, subject)
);
CREATE INDEX login_method_account_id_idx ON identity.login_method (account_id);
CREATE INDEX login_method_phone_idx ON identity.login_method (phone) WHERE phone IS NOT NULL;
CREATE INDEX login_method_email_idx ON identity.login_method (email) WHERE email IS NOT NULL;

--changeset nails-gag:identity-0001-session
--comment: в базе только SHA-256 токена
CREATE TABLE identity.session (
    id              uuid        PRIMARY KEY,
    token_hash      bytea       NOT NULL CONSTRAINT session_token_hash_key UNIQUE,
    account_id      uuid        NOT NULL CONSTRAINT session_account_id_fkey REFERENCES identity.account (id) ON DELETE CASCADE,
    login_method_id uuid        NOT NULL CONSTRAINT session_login_method_id_fkey REFERENCES identity.login_method (id) ON DELETE CASCADE,
    created_at      timestamptz NOT NULL,
    last_used_at    timestamptz NOT NULL
);
CREATE INDEX session_account_id_idx ON identity.session (account_id);

--changeset nails-gag:identity-0001-pending-registration
--comment: незавершённая регистрация или привязка; живёт 30 минут, профиль провайдера лежит только здесь
CREATE TABLE identity.pending_registration (
    id           uuid         PRIMARY KEY,
    ticket_hash  bytea        NOT NULL CONSTRAINT pending_registration_ticket_hash_key UNIQUE,
    purpose      varchar(16)  NOT NULL CONSTRAINT pending_registration_purpose_check CHECK (purpose IN ('REGISTER', 'LINK')),
    provider     varchar(16)  NOT NULL,
    subject      varchar(128) NOT NULL,
    phone        varchar(16),
    email        varchar(254),
    display_name varchar(200),
    avatar_url   varchar(1024),
    client_id    uuid,          -- REGISTER: карточка без аккаунта, к которой привяжемся (может быть null)
    account_id   uuid,          -- LINK: аккаунт вошедшего; REGISTER: null
    other_account_id uuid,      -- LINK: аккаунт, которому принадлежит способ входа (объединение)
    created_at   timestamptz  NOT NULL
);

--changeset nails-gag:identity-0001-role
--comment: роли и их уровни по разделам; строки с NONE не хранятся
CREATE TABLE identity.role (
    id       uuid         PRIMARY KEY,
    code     varchar(64)  NOT NULL CONSTRAINT role_code_key UNIQUE,
    title    varchar(100) NOT NULL,
    built_in boolean      NOT NULL
);

CREATE TABLE identity.role_grant (
    role_id uuid        NOT NULL CONSTRAINT role_grant_role_id_fkey REFERENCES identity.role (id) ON DELETE CASCADE,
    section varchar(32) NOT NULL,
    level   varchar(8)  NOT NULL CONSTRAINT role_grant_level_check CHECK (level IN ('READ', 'WRITE')),
    CONSTRAINT role_grant_pkey PRIMARY KEY (role_id, section)
);

--changeset nails-gag:identity-0001-account-role
CREATE TABLE identity.account_role (
    account_id uuid NOT NULL CONSTRAINT account_role_account_id_fkey REFERENCES identity.account (id) ON DELETE CASCADE,
    role_id    uuid NOT NULL CONSTRAINT account_role_role_id_fkey REFERENCES identity.role (id),
    CONSTRAINT account_role_pkey PRIMARY KEY (account_id, role_id)
);
CREATE INDEX account_role_role_id_idx ON identity.account_role (role_id);

--changeset nails-gag:identity-0001-access-override
--comment: личное переопределение уровня; NONE — явный запрет
CREATE TABLE identity.access_override (
    account_id uuid        NOT NULL CONSTRAINT access_override_account_id_fkey REFERENCES identity.account (id) ON DELETE CASCADE,
    section    varchar(32) NOT NULL,
    level      varchar(8)  NOT NULL CONSTRAINT access_override_level_check CHECK (level IN ('NONE', 'READ', 'WRITE')),
    CONSTRAINT access_override_pkey PRIMARY KEY (account_id, section)
);

--changeset nails-gag:identity-0001-access-change
--comment: журнал прав, только добавление
CREATE TABLE identity.access_change (
    id                uuid        PRIMARY KEY,
    actor_account_id  uuid,       -- null у BOOTSTRAP
    target_account_id uuid,
    role_id           uuid,
    kind              varchar(32) NOT NULL,
    section           varchar(32),
    old_level         varchar(8),
    new_level         varchar(8),
    changed_at        timestamptz NOT NULL
);
CREATE INDEX access_change_changed_at_idx ON identity.access_change (changed_at DESC, id DESC);

--changeset nails-gag:identity-0001-login-event
--comment: журнал входов; хранится 90 дней
CREATE TABLE identity.login_event (
    id          uuid         PRIMARY KEY,
    account_id  uuid         CONSTRAINT login_event_account_id_fkey REFERENCES identity.account (id) ON DELETE SET NULL,
    provider    varchar(16)  NOT NULL,
    outcome     varchar(16)  NOT NULL CONSTRAINT login_event_outcome_check CHECK (outcome IN ('SIGNED_IN', 'REGISTERED', 'LINKED', 'BLOCKED', 'FAILED')),
    ip          varchar(45),
    user_agent  varchar(512),
    occurred_at timestamptz  NOT NULL
);
CREATE INDEX login_event_occurred_at_idx ON identity.login_event (occurred_at DESC, id DESC);
CREATE INDEX login_event_account_id_idx ON identity.login_event (account_id);
