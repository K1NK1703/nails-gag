--liquibase formatted sql

--changeset nails-gag:files-0001-schema
CREATE SCHEMA IF NOT EXISTS files;

--changeset nails-gag:files-0001-stored-file
--comment: загруженная картинка; варианты лежат на диске в <root>/<purpose>/<id>/<ширина>.jpg, оригинал не хранится
CREATE TABLE files.stored_file (
    id          uuid        PRIMARY KEY,
    purpose     varchar(16) NOT NULL CONSTRAINT stored_file_purpose_check CHECK (purpose IN ('PORTFOLIO', 'REFERENCE')),
    width       integer     NOT NULL CONSTRAINT stored_file_width_check CHECK (width > 0),     -- после поворота по EXIF, до уменьшения
    height      integer     NOT NULL CONSTRAINT stored_file_height_check CHECK (height > 0),
    sha256      varchar(64) NOT NULL CONSTRAINT stored_file_sha256_check CHECK (sha256 ~ '^[0-9a-f]{64}$'), -- загруженного содержимого
    size_bytes  bigint      NOT NULL CONSTRAINT stored_file_size_bytes_check CHECK (size_bytes > 0),
    uploaded_by uuid        NOT NULL,  -- identity.account; внешних ключей между схемами нет
    attached_at timestamptz,           -- NULL — «сирота»: владелец ещё не привязал файл, уборка удалит его через сутки
    created_at  timestamptz NOT NULL
);

-- Уборка ищет «сирот» по времени загрузки; привязанных файлов в индексе нет.
CREATE INDEX stored_file_orphan_created_at_idx ON files.stored_file (created_at) WHERE attached_at IS NULL;
