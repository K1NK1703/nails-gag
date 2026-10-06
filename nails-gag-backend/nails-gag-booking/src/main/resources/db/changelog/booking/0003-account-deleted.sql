--liquibase formatted sql

--changeset nails-gag:booking-0003-account-deleted
--comment: новая причина отмены — клиент удалил аккаунт
ALTER TABLE booking.booking
    DROP CONSTRAINT booking_cancel_reason_check,
    ADD CONSTRAINT booking_cancel_reason_check
        CHECK (cancel_reason IN ('CLIENT', 'CLIENT_LATE', 'MASTER', 'LATE_ARRIVAL', 'PREPAYMENT_TIMEOUT', 'ACCOUNT_DELETED'));
