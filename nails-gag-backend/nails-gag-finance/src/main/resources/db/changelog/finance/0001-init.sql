--liquibase formatted sql

--changeset nails-gag:finance-0001-schema
CREATE SCHEMA IF NOT EXISTS finance;

--changeset nails-gag:finance-0001-category
--comment: категории ручных доходов и расходов; не удаляются, а скрываются — на них ссылаются записи и шаблоны
CREATE TABLE finance.category (
    id         uuid        PRIMARY KEY,
    kind       varchar(8)  NOT NULL CONSTRAINT category_kind_check CHECK (kind IN ('INCOME', 'EXPENSE')),
    name       text        NOT NULL CONSTRAINT category_name_check CHECK (char_length(name) BETWEEN 1 AND 60),
    hidden     boolean     NOT NULL,
    position   integer     NOT NULL CONSTRAINT category_position_check CHECK (position >= 0),
    created_at timestamptz NOT NULL,
    -- Цель составных внешних ключей (category_id, kind) записей и шаблонов: их вид всегда равен виду категории.
    CONSTRAINT category_id_kind_key UNIQUE (id, kind),
    -- Порядок в списке вида; индекс отдаёт список сразу в порядке. Проверяется сразу; перестановка откладывает
    -- проверку на время своих изменений (SET CONSTRAINTS … DEFERRED) и возвращает её до конца метода.
    -- Откладываемое ограничение не может быть целью ON CONFLICT: вставки с ON CONFLICT — только с явной целью.
    CONSTRAINT category_kind_position_key UNIQUE (kind, position) DEFERRABLE INITIALLY IMMEDIATE
);

-- Имя уникально в пределах вида без учёта регистра; модуль переводит нарушение в finance.category_name_taken.
CREATE UNIQUE INDEX category_kind_name_key ON finance.category (kind, lower(name));

--changeset nails-gag:finance-0001-category-seed
--comment: стартовые категории
INSERT INTO finance.category (id, kind, name, hidden, position, created_at)
VALUES ('019a2f00-0000-7000-8000-000000000001', 'EXPENSE', 'Материалы', false, 0, now()),
       ('019a2f00-0000-7000-8000-000000000002', 'EXPENSE', 'Аренда', false, 1, now()),
       ('019a2f00-0000-7000-8000-000000000003', 'EXPENSE', 'Обучение', false, 2, now()),
       ('019a2f00-0000-7000-8000-000000000004', 'EXPENSE', 'Инструменты', false, 3, now()),
       ('019a2f00-0000-7000-8000-000000000005', 'EXPENSE', 'Реклама', false, 4, now()),
       ('019a2f00-0000-7000-8000-000000000006', 'EXPENSE', 'Прочее', false, 5, now()),
       ('019a2f00-0000-7000-8000-000000000101', 'INCOME', 'Чаевые', false, 0, now()),
       ('019a2f00-0000-7000-8000-000000000102', 'INCOME', 'Продажа товаров', false, 1, now()),
       ('019a2f00-0000-7000-8000-000000000103', 'INCOME', 'Прочее', false, 2, now());

--changeset nails-gag:finance-0001-recurring
--comment: шаблон повторяющейся ежемесячной записи; в коротком месяце day_of_month 29–31 — последний день месяца
CREATE TABLE finance.recurring (
    id           uuid           PRIMARY KEY,
    kind         varchar(8)     NOT NULL CONSTRAINT recurring_kind_check CHECK (kind IN ('INCOME', 'EXPENSE')),
    -- Индекса нет: категории не удаляются и не меняют id и вид, проверять ссылки при их изменении не приходится.
    category_id  uuid           NOT NULL,
    amount       numeric(12, 2) NOT NULL CONSTRAINT recurring_amount_check CHECK (amount > 0),
    comment      text           CONSTRAINT recurring_comment_check CHECK (char_length(comment) BETWEEN 1 AND 500),
    day_of_month integer        NOT NULL CONSTRAINT recurring_day_of_month_check CHECK (day_of_month BETWEEN 1 AND 31),
    starts_on    date           NOT NULL CONSTRAINT recurring_starts_on_check CHECK (starts_on >= DATE '2000-01-01'),
    ends_on      date,
    paused       boolean        NOT NULL,
    -- Прогресс ночной задачи: по какой день включительно записи шаблона уже созданы; NULL — ещё ни одной. Задача
    -- создаёт записи только за дни после него и сдвигает его в той же транзакции: перенесённая или удалённая запись
    -- шаблона за свой исходный день заново не создаётся.
    generated_through date,
    created_at   timestamptz    NOT NULL,
    updated_at   timestamptz    NOT NULL,
    CONSTRAINT recurring_ends_on_check CHECK (ends_on >= starts_on),
    CONSTRAINT recurring_category_fkey FOREIGN KEY (category_id, kind) REFERENCES finance.category (id, kind)
);

--changeset nails-gag:finance-0001-entry
--comment: ручной доход или расход; доход от визитов и предоплат живёт в booking, копии здесь нет
CREATE TABLE finance.entry (
    id           uuid           PRIMARY KEY,
    kind         varchar(8)     NOT NULL CONSTRAINT entry_kind_check CHECK (kind IN ('INCOME', 'EXPENSE')),
    category_id  uuid           NOT NULL,
    amount       numeric(12, 2) NOT NULL CONSTRAINT entry_amount_check CHECK (amount > 0),
    occurred_on  date           NOT NULL CONSTRAINT entry_occurred_on_check CHECK (occurred_on >= DATE '2000-01-01'),
    comment      text           CONSTRAINT entry_comment_check CHECK (char_length(comment) BETWEEN 1 AND 500),
    -- Шаблон, которым создана запись (finance.recurring). Без внешнего ключа: шаблон удаляется, а созданные им
    -- записи остаются и по-прежнему считаются созданными шаблоном (быстрый ввод их не предлагает).
    recurring_id uuid,
    created_at   timestamptz    NOT NULL,
    updated_at   timestamptz    NOT NULL,
    CONSTRAINT entry_category_fkey FOREIGN KEY (category_id, kind) REFERENCES finance.category (id, kind),
    -- Защита от двойного создания записи шаблоном за один день; у ручных записей recurring_id пуст. Перенос записи
    -- шаблона на занятый день модуль переводит в ValidationException по полю occurredOn.
    CONSTRAINT entry_recurring_id_occurred_on_key UNIQUE (recurring_id, occurred_on)
);

CREATE INDEX entry_occurred_on_idx ON finance.entry (occurred_on);

CREATE INDEX entry_category_id_idx ON finance.entry (category_id);

--changeset nails-gag:finance-0001-entry-change
--comment: журнал изменений ручных записей: было → стало, кто, когда; в снимке — название категории на момент изменения
CREATE TABLE finance.entry_change (
    id         uuid        PRIMARY KEY,
    -- finance.entry; без внешнего ключа: история удалённой записи остаётся
    entry_id   uuid        NOT NULL,
    action     varchar(8)  NOT NULL CONSTRAINT entry_change_action_check
                               CHECK (action IN ('CREATE', 'UPDATE', 'DELETE')),
    -- Снимки записи (EntrySnapshot): kind, categoryId, categoryName, amount, occurredOn, comment, recurringId.
    before     jsonb,
    after      jsonb,
    -- identity.account; NULL — система (повторяющийся шаблон). Внешних ключей между схемами нет.
    account_id uuid,
    changed_at timestamptz NOT NULL,
    CONSTRAINT entry_change_snapshots_check CHECK (
        (action = 'CREATE' AND before IS NULL AND after IS NOT NULL)
        OR (action = 'UPDATE' AND before IS NOT NULL AND after IS NOT NULL)
        OR (action = 'DELETE' AND before IS NOT NULL AND after IS NULL))
);

CREATE INDEX entry_change_entry_id_changed_at_idx ON finance.entry_change (entry_id, changed_at);

CREATE INDEX entry_change_changed_at_idx ON finance.entry_change (changed_at);
