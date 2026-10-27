package gag.nails.portfolio.internal.consent;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Клиент, разрешивший публикацию фото своих работ: копия согласий модуля клиентов, чтобы правило публичности было
 * частью одного запроса к работам. Строки пишутся и удаляются только запросами {@link PublicationConsentRepository}
 * (повтор согласия — без ошибки ключа); сущность нужна, чтобы запросы к работам могли соединяться с ней.
 */
@Entity
@Table(schema = "portfolio", name = "publication_consent")
class PublicationConsentEntity {

    @Id
    private UUID clientId;

    private Instant givenAt;

    protected PublicationConsentEntity() {}
}
