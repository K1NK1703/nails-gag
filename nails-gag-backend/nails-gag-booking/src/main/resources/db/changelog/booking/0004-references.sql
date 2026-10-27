--liquibase formatted sql

--changeset nails-gag:booking-0004-reference
--comment: фото-референсы к записи, до трёх; файл — модуля files назначения REFERENCE
-- Удаляются вместе с файлами по сроку хранения после визита или отмены и при обезличивании клиента.
CREATE TABLE booking.reference (
    -- без ON DELETE CASCADE: каскад оставил бы файлы привязанными; записи и так не удаляются
    booking_id uuid        NOT NULL CONSTRAINT reference_booking_id_fkey REFERENCES booking.booking (id),
    -- files.stored_file; внешних ключей между схемами нет
    file_id    uuid        NOT NULL CONSTRAINT reference_file_id_key UNIQUE,
    added_by   varchar(8)  NOT NULL CONSTRAINT reference_added_by_check CHECK (added_by IN ('CLIENT', 'STAFF')),
    created_at timestamptz NOT NULL,
    CONSTRAINT reference_pkey PRIMARY KEY (booking_id, file_id)
);
