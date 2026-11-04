--liquibase formatted sql

--changeset nails-gag:identity-0003-analytics-developer-grant
--comment: раздел ANALYTICS: «Разработчику» — чтение, как в Actor.developerAccess(); строки «Мастера» не меняются
-- «Мастеру» раздел закрыт: NONE в role_grant не хранится (CHECK уровня — только READ и WRITE), отсутствие строки
-- и есть NONE. Мастеру раздел выдаётся личным переопределением на странице доступа.
-- Встроенные роли правятся со страницы доступа, но раздела до этой миграции не было, поэтому своей строки у роли
-- быть не может; ON CONFLICT — на случай, если строку уже добавили руками, чтобы не перезаписать чужое решение.
INSERT INTO identity.role_grant (role_id, section, level)
VALUES ('019a0c00-0000-7000-8000-000000000402', 'ANALYTICS', 'READ')
ON CONFLICT (role_id, section) DO NOTHING;
