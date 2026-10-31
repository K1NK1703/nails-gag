--liquibase formatted sql

--changeset nails-gag:booking-0005-columns
--comment: финансы: причина итоговой суммы визита, сумма предоплаты и ручное переопределение её исхода
ALTER TABLE booking.booking
    ADD COLUMN amount_note                 text           CONSTRAINT booking_amount_note_check
                                                          CHECK (char_length(amount_note) BETWEEN 1 AND 200),
    ADD COLUMN prepayment_amount           numeric(12, 2) CONSTRAINT booking_prepayment_amount_check
                                                          CHECK (prepayment_amount > 0),
    ADD COLUMN prepayment_outcome_override varchar(8)     CONSTRAINT booking_prepayment_outcome_override_check
                                                          CHECK (prepayment_outcome_override IN ('KEPT', 'RETURNED'));

--changeset nails-gag:booking-0005-prepayment-amount
--comment: сумма уже полученных предоплат по правилу модуля (BookingRules.defaultPrepaymentAmount)
-- процент предоплаты из правил записи от прайса выбранного при записи состава (PLANNED), до рубля: round
-- для numeric округляет половину от нуля, то есть вверх; не меньше рубля
UPDATE booking.booking b
SET prepayment_amount = greatest(1, round(
        coalesce((SELECT sum(s.price) FROM booking.booking_service s
                  WHERE s.booking_id = b.id AND s.phase = 'PLANNED'), 0)
        * (SELECT prepayment_percent FROM booking.settings) / 100, 0))
WHERE b.prepayment_received_at IS NOT NULL;

--changeset nails-gag:booking-0005-constraints
--comment: сумма — у полученной предоплаты, причина суммы — у выполненного визита, переопределение — у отмен и неявок
ALTER TABLE booking.booking
    ADD CONSTRAINT booking_prepayment_amount_received_check
        CHECK ((prepayment_received_at IS NULL) = (prepayment_amount IS NULL)),
    ADD CONSTRAINT booking_amount_note_completed_check
        CHECK (amount_note IS NULL OR status = 'COMPLETED'),
    ADD CONSTRAINT booking_prepayment_outcome_override_status_check
        CHECK (prepayment_outcome_override IS NULL OR status IN ('CANCELLED', 'NO_SHOW'));
