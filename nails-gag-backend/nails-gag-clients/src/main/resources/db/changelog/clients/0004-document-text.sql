--liquibase formatted sql

--changeset nails-gag:clients-0004-document-text
--comment: полный текст редакции; пусто только у двух стартовых редакций из 0002-seed
ALTER TABLE clients.document_version ADD COLUMN text text;
ALTER TABLE clients.document_version
    ADD CONSTRAINT document_version_text_check CHECK (text IS NULL OR length(text) BETWEEN 1 AND 200000);
