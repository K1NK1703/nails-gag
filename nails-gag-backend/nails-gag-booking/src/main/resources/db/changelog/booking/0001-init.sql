--liquibase formatted sql

--changeset nails-gag:booking-0001-schema
CREATE SCHEMA IF NOT EXISTS booking;

--changeset nails-gag:booking-0001-settings
--comment: одна строка правил записи: singleton не даёт завести вторую; каждое изменение — строка settings_change
CREATE TABLE booking.settings (
    id                             uuid        PRIMARY KEY,
    singleton                      boolean     NOT NULL DEFAULT true
                                               CONSTRAINT settings_singleton_check CHECK (singleton)
                                               CONSTRAINT settings_singleton_key UNIQUE,
    max_active_bookings            integer     NOT NULL CONSTRAINT settings_max_active_bookings_check CHECK (max_active_bookings >= 1),
    booking_cutoff_minutes         integer     NOT NULL CONSTRAINT settings_booking_cutoff_minutes_check CHECK (booking_cutoff_minutes >= 0),
    prepayment_hold_hours          integer     NOT NULL CONSTRAINT settings_prepayment_hold_hours_check CHECK (prepayment_hold_hours >= 1),
    free_cancel_window_hours       integer     NOT NULL CONSTRAINT settings_free_cancel_window_hours_check CHECK (free_cancel_window_hours >= 0),
    prepayment_percent             integer     NOT NULL CONSTRAINT settings_prepayment_percent_check CHECK (prepayment_percent BETWEEN 1 AND 100),
    late_arrival_tolerance_minutes integer     NOT NULL CONSTRAINT settings_late_arrival_tolerance_minutes_check CHECK (late_arrival_tolerance_minutes >= 0),
    mark_correction_window_days    integer     NOT NULL CONSTRAINT settings_mark_correction_window_days_check CHECK (mark_correction_window_days >= 0),
    updated_at                     timestamptz NOT NULL
);

CREATE TABLE booking.settings_change (
    id         uuid        PRIMARY KEY,
    field      varchar(64) NOT NULL,
    old_value  varchar(64) NOT NULL,
    new_value  varchar(64) NOT NULL,
    changed_at timestamptz NOT NULL
);

CREATE INDEX settings_change_changed_at_idx ON booking.settings_change (changed_at DESC);

--changeset nails-gag:booking-0001-booking
--comment: запись клиента на слот; client_id и slot_id — идентификаторы из схем clients и schedule, без внешних ключей между схемами
CREATE TABLE booking.booking (
    id                     uuid           PRIMARY KEY,
    client_id              uuid           NOT NULL,
    slot_id                uuid           NOT NULL,
    slot_starts_at         timestamptz    NOT NULL,
    slot_ends_at           timestamptz    NOT NULL,
    status                 varchar(24)    NOT NULL CONSTRAINT booking_status_check
                                          CHECK (status IN ('AWAITING_PREPAYMENT', 'CONFIRMED', 'COMPLETED', 'NO_SHOW', 'CANCELLED')),
    created_by             varchar(8)     NOT NULL CONSTRAINT booking_created_by_check CHECK (created_by IN ('CLIENT', 'MASTER')),
    comment                text           CONSTRAINT booking_comment_check CHECK (char_length(comment) <= 500),
    master_note            text,
    prepayment_required    boolean        NOT NULL,
    prepayment_received_at timestamptz,
    prepayment_deadline    timestamptz,
    proposed_start         timestamptz,
    proposed_at            timestamptz,
    offer_status           varchar(16)    CONSTRAINT booking_offer_status_check
                                          CHECK (offer_status IN ('PENDING', 'ACCEPTED', 'DECLINED', 'EXPIRED', 'WITHDRAWN')),
    agreed_start           timestamptz,
    cancel_reason          varchar(24)    CONSTRAINT booking_cancel_reason_check
                                          CHECK (cancel_reason IN ('CLIENT', 'CLIENT_LATE', 'MASTER', 'LATE_ARRIVAL', 'PREPAYMENT_TIMEOUT')),
    cancelled_at           timestamptz,
    completed_at           timestamptz,
    no_show_at             timestamptz,
    final_amount           numeric(12, 2) CONSTRAINT booking_final_amount_check CHECK (final_amount >= 0),
    created_at             timestamptz    NOT NULL,
    updated_at             timestamptz    NOT NULL,
    CONSTRAINT booking_slot_period_check CHECK (slot_ends_at > slot_starts_at),
    CONSTRAINT booking_cancelled_check CHECK ((status = 'CANCELLED') = (cancel_reason IS NOT NULL)),
    CONSTRAINT booking_cancelled_at_check CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL)),
    CONSTRAINT booking_completed_check CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL)
                                              AND (status = 'COMPLETED') = (final_amount IS NOT NULL)),
    CONSTRAINT booking_no_show_check CHECK ((status = 'NO_SHOW') = (no_show_at IS NOT NULL)),
    CONSTRAINT booking_prepayment_deadline_check CHECK ((status = 'AWAITING_PREPAYMENT') = (prepayment_deadline IS NOT NULL)),
    CONSTRAINT booking_offer_check CHECK ((offer_status IS NULL) = (proposed_start IS NULL)
                                          AND (offer_status IS NULL) = (proposed_at IS NULL)),
    CONSTRAINT booking_agreed_start_check CHECK (agreed_start IS NULL OR offer_status = 'ACCEPTED')
);

-- второй рубеж после условного занятия слота в schedule: на один слот не больше одной активной записи
CREATE UNIQUE INDEX booking_active_slot_key ON booking.booking (slot_id)
    WHERE status IN ('AWAITING_PREPAYMENT', 'CONFIRMED');
CREATE INDEX booking_client_id_idx ON booking.booking (client_id);
CREATE INDEX booking_slot_starts_at_idx ON booking.booking (slot_starts_at);

--changeset nails-gag:booking-0001-booking-service
--comment: состав записи — снимок названий и цен услуг; PLANNED выбрано при записи, ACTUAL — фактически оказано
CREATE TABLE booking.booking_service (
    id         uuid           PRIMARY KEY,
    booking_id uuid           NOT NULL CONSTRAINT booking_service_booking_id_fkey REFERENCES booking.booking (id) ON DELETE CASCADE,
    service_id uuid           NOT NULL,
    kind       varchar(16)    NOT NULL CONSTRAINT booking_service_kind_check CHECK (kind IN ('MAIN', 'ADDON')),
    title      varchar(200)   NOT NULL,
    price      numeric(12, 2) NOT NULL CONSTRAINT booking_service_price_check CHECK (price >= 0),
    phase      varchar(8)     NOT NULL CONSTRAINT booking_service_phase_check CHECK (phase IN ('PLANNED', 'ACTUAL')),
    sort_order integer        NOT NULL
);

CREATE INDEX booking_service_booking_id_idx ON booking.booking_service (booking_id);

--changeset nails-gag:booking-0001-client-lock
--comment: строка на клиента для SELECT ... FOR UPDATE при проверке лимита активных записей; id — идентификатор клиента
CREATE TABLE booking.client_lock (
    id uuid PRIMARY KEY
);
