--liquibase formatted sql

--changeset nails-gag:app-0001-btree-gist
--comment: ограничение-исключение по интервалам времени для слотов (schedule) требует btree_gist
CREATE EXTENSION IF NOT EXISTS btree_gist;
