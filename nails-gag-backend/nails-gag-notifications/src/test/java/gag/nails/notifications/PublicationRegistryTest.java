package gag.nails.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.core.EventPublicationRepository;

/**
 * Реестр публикаций Spring Modulith работает на таблице из миграции модуля: схема {@code modulith}, структура v2.
 * Сама повторная доставка после рестарта проверяется вместе со слушателями.
 */
@NotificationsModuleTest
class PublicationRegistryTest {

    @Autowired
    EventPublicationRepository publications;

    @Autowired
    JdbcClient jdbc;

    @Test
    void registryReadsTableFromModulithSchema() {
        assertThat(publications.findIncompletePublications()).isNotNull();
        assertThat(jdbc.sql("""
                        select column_name from information_schema.columns
                        where table_schema = 'modulith' and table_name = 'event_publication'
                        """).query(String.class).list())
                .containsExactlyInAnyOrder(
                        "id",
                        "listener_id",
                        "event_type",
                        "serialized_event",
                        "publication_date",
                        "completion_date",
                        "status",
                        "completion_attempts",
                        "last_resubmission_date");
    }
}
