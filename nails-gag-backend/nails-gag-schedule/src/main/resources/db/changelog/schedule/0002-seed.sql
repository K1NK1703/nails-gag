--liquibase formatted sql

--changeset nails-gag:schedule-0002-seed
--comment: стартовая длительность слота и пустая ротация; якорь — понедельник недели перед началом работы над графиком
INSERT INTO schedule.settings (id, slot_duration_minutes, updated_at)
VALUES ('019a0c00-0000-7000-8000-000000000201', 120, now());

INSERT INTO schedule.rotation (id, anchor_monday, anchor_index, updated_at)
VALUES ('019a0c00-0000-7000-8000-000000000202', '2026-09-28', 0, now());
