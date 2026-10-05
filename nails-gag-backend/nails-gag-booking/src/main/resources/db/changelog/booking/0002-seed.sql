--liquibase formatted sql

--changeset nails-gag:booking-0002-seed
--comment: стартовые правила записи из оферты
INSERT INTO booking.settings (
    id, max_active_bookings, booking_cutoff_minutes, prepayment_hold_hours, free_cancel_window_hours,
    prepayment_percent, late_arrival_tolerance_minutes, mark_correction_window_days, updated_at)
VALUES ('019a0c00-0000-7000-8000-000000000301', 1, 120, 24, 24, 50, 15, 7, now());
