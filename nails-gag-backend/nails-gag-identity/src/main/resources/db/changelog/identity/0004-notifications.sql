--liquibase formatted sql

--changeset nails-gag:identity-0004-notifications-built-in-grants
--comment: раздел NOTIFICATIONS: «Мастеру» и «Разработчику» — изменение, как в Actor.masterAccess() и developerAccess()
-- Встроенные роли правятся со страницы доступа, но добавить через неё строку NOTIFICATIONS было нельзя: раздела до
-- этой миграции не было. ON CONFLICT — только на случай ручной правки базы: такая строка остаётся как есть.
INSERT INTO identity.role_grant (role_id, section, level)
VALUES ('019a0c00-0000-7000-8000-000000000401', 'NOTIFICATIONS', 'WRITE'),
       ('019a0c00-0000-7000-8000-000000000402', 'NOTIFICATIONS', 'WRITE')
ON CONFLICT (role_id, section) DO NOTHING;
