--liquibase formatted sql

--changeset nails-gag:clients-0003-identity-support
--comment: номер для связи (не подтверждён, не уникален), блокировка с причиной и обезличивание карточки
ALTER TABLE clients.client
    ADD COLUMN contact_phone varchar(16),
    ADD COLUMN blocked_at    timestamptz,
    ADD COLUMN block_reason  text,
    ADD COLUMN erased_at     timestamptz,
    ADD CONSTRAINT client_block_reason_check
        CHECK (block_reason IS NULL OR (blocked_at IS NOT NULL AND char_length(block_reason) <= 500)),
    ADD CONSTRAINT client_erased_check
        CHECK (erased_at IS NULL
               OR (phone IS NULL AND contact_phone IS NULL AND email IS NULL AND account_id IS NULL
                   AND last_name IS NULL AND patronymic IS NULL AND birth_day IS NULL AND birth_month IS NULL
                   AND master_note IS NULL AND block_reason IS NULL));
