--liquibase formatted sql

--changeset nails-gag:clients-0002-seed-document-versions
INSERT INTO clients.document_version (id, kind, version, effective_from)
VALUES ('019a0c00-0000-7000-8000-000000000101', 'POLICY', '2026-09-25', '2026-09-25'),
       ('019a0c00-0000-7000-8000-000000000102', 'OFFER', '2026-09-25', '2026-09-25');
