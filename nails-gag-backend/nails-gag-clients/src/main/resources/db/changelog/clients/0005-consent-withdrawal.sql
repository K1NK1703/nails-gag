--liquibase formatted sql

--changeset nails-gag:clients-0005-consent-withdrawn-at
--comment: согласие на публикацию фото; пометка отзыва на строке согласия — фильтр «действующее», строка не удаляется
ALTER TABLE clients.consent
    DROP CONSTRAINT consent_kind_check,
    ADD CONSTRAINT consent_kind_check
        CHECK (kind IN ('PERSONAL_DATA', 'OFFER', 'REVIEW_PUBLICATION', 'PHOTO_PUBLICATION')),
    ADD COLUMN withdrawn_at timestamptz,
    ADD CONSTRAINT consent_withdrawn_at_check CHECK (withdrawn_at IS NULL OR withdrawn_at >= given_at);

--changeset nails-gag:clients-0005-consent-withdrawal
--comment: журнал отзывов согласий, только добавление: одна запись на один отзыв, сколько бы строк согласий он ни снял
CREATE TABLE clients.consent_withdrawal (
    id           uuid PRIMARY KEY,
    client_id    uuid NOT NULL CONSTRAINT consent_withdrawal_client_id_fkey REFERENCES clients.client (id),
    kind         varchar(32) NOT NULL
                     CONSTRAINT consent_withdrawal_kind_check
                         CHECK (kind IN ('PERSONAL_DATA', 'OFFER', 'REVIEW_PUBLICATION', 'PHOTO_PUBLICATION')),
    withdrawn_at timestamptz NOT NULL,
    withdrawn_by varchar(16) NOT NULL
                     CONSTRAINT consent_withdrawal_withdrawn_by_check CHECK (withdrawn_by IN ('CLIENT', 'STAFF'))
);

CREATE INDEX consent_withdrawal_client_idx ON clients.consent_withdrawal (client_id, withdrawn_at DESC);
