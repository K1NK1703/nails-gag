--liquibase formatted sql

--changeset nails-gag:clients-0001-schema
CREATE SCHEMA IF NOT EXISTS clients;

--changeset nails-gag:clients-0001-client
--comment: клиент: карточка от мастера (без аккаунта) или зарегистрированный; account_id заполнит модуль identity
CREATE TABLE clients.client (
    id                  uuid PRIMARY KEY,
    first_name          varchar(100) NOT NULL,
    last_name           varchar(100),
    patronymic          varchar(100),
    phone               varchar(16) CONSTRAINT client_phone_key UNIQUE,
    email               varchar(254),
    birth_day           integer CONSTRAINT client_birth_day_check CHECK (birth_day BETWEEN 1 AND 31),
    birth_month         integer CONSTRAINT client_birth_month_check CHECK (birth_month BETWEEN 1 AND 12),
    master_note         text,
    source              varchar(32) NOT NULL
                            CONSTRAINT client_source_check CHECK (source IN ('SELF_REGISTERED', 'ADDED_BY_MASTER')),
    account_id          uuid CONSTRAINT client_account_id_key UNIQUE,
    prepayment_required boolean NOT NULL DEFAULT false,
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL
);

--changeset nails-gag:clients-0001-prepayment-flag-change
--comment: журнал признака обязательной предоплаты: кто, когда, почему
CREATE TABLE clients.prepayment_flag_change (
    id         uuid PRIMARY KEY,
    client_id  uuid NOT NULL CONSTRAINT prepayment_flag_change_client_id_fkey REFERENCES clients.client (id),
    required   boolean NOT NULL,
    -- без CHECK: набор причин открытый, новые добавляются без миграции
    reason     varchar(48) NOT NULL,
    set_by     varchar(16) NOT NULL
                   CONSTRAINT prepayment_flag_change_set_by_check CHECK (set_by IN ('SYSTEM', 'MASTER')),
    booking_id uuid,
    comment    text,
    changed_at timestamptz NOT NULL
);

CREATE INDEX prepayment_flag_change_client_idx ON clients.prepayment_flag_change (client_id, changed_at DESC);

--changeset nails-gag:clients-0001-document-version
--comment: версии политики и оферты; действующая — последняя по effective_from не позже сегодня
CREATE TABLE clients.document_version (
    id             uuid PRIMARY KEY,
    kind           varchar(16) NOT NULL CONSTRAINT document_version_kind_check CHECK (kind IN ('POLICY', 'OFFER')),
    version        varchar(64) NOT NULL,
    effective_from date NOT NULL,
    CONSTRAINT document_version_kind_version_key UNIQUE (kind, version),
    CONSTRAINT document_version_kind_effective_from_key UNIQUE (kind, effective_from)
);

--changeset nails-gag:clients-0001-consent
--comment: журнал согласий, только добавление
CREATE TABLE clients.consent (
    id               uuid PRIMARY KEY,
    client_id        uuid NOT NULL CONSTRAINT consent_client_id_fkey REFERENCES clients.client (id),
    kind             varchar(32) NOT NULL
                         CONSTRAINT consent_kind_check CHECK (kind IN ('PERSONAL_DATA', 'OFFER', 'REVIEW_PUBLICATION')),
    document_version varchar(64) NOT NULL,
    given_at         timestamptz NOT NULL,
    source           varchar(32) NOT NULL
                         CONSTRAINT consent_source_check CHECK (source IN ('SITE_CHECKBOX', 'ENTERED_BY_MASTER'))
);

CREATE INDEX consent_client_idx ON clients.consent (client_id, given_at DESC);
