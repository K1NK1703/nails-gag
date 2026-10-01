--liquibase formatted sql

--changeset nails-gag:catalog-0002-seed
--comment: стартовый прайс с лендинга
INSERT INTO catalog.service (id, kind, title, description, price, duration_minutes, category, sort_order,
                             show_on_landing, highlight_on_landing, active, created_at, updated_at)
VALUES ('019a0c00-0000-7000-8000-000000000001', 'MAIN', 'Маникюр',
        'Любой исходник · аппаратная техника · покрытие гель-лаком', 2500.00, 120, 'Основная услуга', 0,
        true, true, true, now(), now()),
       ('019a0c00-0000-7000-8000-000000000002', 'ADDON', 'Френч',
        'Классический или цветной — на ваш выбор', 300.00, NULL, 'Доп. покрытие', 1,
        true, false, true, now(), now()),
       ('019a0c00-0000-7000-8000-000000000003', 'ADDON', 'Втирка / дизайн',
        'На все ногти — мягкие переливы, флоры, акценты', 200.00, NULL, 'Дизайн', 2,
        true, false, true, now(), now());
