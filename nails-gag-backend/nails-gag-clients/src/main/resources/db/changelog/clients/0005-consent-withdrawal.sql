--liquibase formatted sql

--changeset nails-gag:clients-0005-consent-withdrawal
--comment: согласие на публикацию фото и отзыв согласий: строка журнала помечается отозванной, но не удаляется
ALTER TABLE clients.consent DROP CONSTRAINT consent_kind_check;
ALTER TABLE clients.consent
    ADD CONSTRAINT consent_kind_check
        CHECK (kind IN ('PERSONAL_DATA', 'OFFER', 'REVIEW_PUBLICATION', 'PHOTO_PUBLICATION')),
    ADD COLUMN withdrawn_at timestamptz,
    ADD COLUMN withdrawn_by varchar(16)
        CONSTRAINT consent_withdrawn_by_check CHECK (withdrawn_by IN ('CLIENT', 'STAFF')),
    ADD CONSTRAINT consent_withdrawal_check CHECK ((withdrawn_at IS NULL) = (withdrawn_by IS NULL));
