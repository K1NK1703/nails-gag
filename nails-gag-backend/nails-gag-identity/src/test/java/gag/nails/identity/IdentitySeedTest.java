package gag.nails.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import gag.nails.common.actor.AccessLevel;
import gag.nails.common.actor.Actor;
import gag.nails.common.actor.Section;
import gag.nails.testsupport.ModuleTest;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Сид встроенных ролей читается напрямую из базы: {@code AccessAdministration} появится в следующих
 * задачах, а другого публичного способа увидеть роли пока нет.
 */
@ModuleTest
class IdentitySeedTest {

    @Autowired
    JdbcClient jdbc;

    @Test
    void builtInRolesMatchActorDefaults() {
        var roles = jdbc.sql("select code, title, built_in from identity.role order by code")
                .query()
                .listOfRows();

        assertThat(roles)
                .extracting(row -> row.get("code"), row -> row.get("title"), row -> row.get("built_in"))
                .containsExactly(tuple("DEVELOPER", "Разработчик", true), tuple("MASTER", "Мастер", true));
        assertThat(grantsOf("MASTER")).containsExactlyInAnyOrderEntriesOf(Actor.masterAccess());
        assertThat(grantsOf("DEVELOPER")).containsExactlyInAnyOrderEntriesOf(Actor.developerAccess());
    }

    private Map<Section, AccessLevel> grantsOf(String code) {
        var grants = new EnumMap<Section, AccessLevel>(Section.class);
        jdbc.sql("""
                        select g.section, g.level
                        from identity.role_grant g
                        join identity.role r on r.id = g.role_id
                        where r.code = :code
                        """)
                .param("code", code)
                .query()
                .listOfRows()
                .forEach(row -> grants.put(
                        Section.valueOf((String) row.get("section")), AccessLevel.valueOf((String) row.get("level"))));
        return grants;
    }
}
