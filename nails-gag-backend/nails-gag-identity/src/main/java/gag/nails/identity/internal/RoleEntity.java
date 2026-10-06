package gag.nails.identity.internal;

import gag.nails.common.actor.AccessLevel;
import gag.nails.common.actor.Section;
import gag.nails.identity.RoleView;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.MapKeyEnumerated;
import jakarta.persistence.Table;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * Роль с уровнями по разделам. Уровни — строки {@code role_grant}, часть роли: меняются только вместе
 * с ней; {@link AccessLevel#NONE} не хранится.
 */
@Entity
@Table(schema = "identity", name = "role")
class RoleEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    private String code;
    private String title;
    private boolean builtIn;

    @ElementCollection
    @CollectionTable(schema = "identity", name = "role_grant", joinColumns = @JoinColumn(name = "role_id"))
    @MapKeyColumn(name = "section")
    @MapKeyEnumerated(EnumType.STRING)
    @Column(name = "level")
    @Enumerated(EnumType.STRING)
    private Map<Section, AccessLevel> grants = new EnumMap<>(Section.class);

    protected RoleEntity() {}

    /** Роль из конструктора ролей; встроенные роли сеет миграция. */
    RoleEntity(String code, String title, Map<Section, AccessLevel> grants) {
        this.code = code;
        this.title = title;
        this.builtIn = false;
        replaceGrants(grants);
    }

    void rename(String title) {
        this.title = title;
    }

    /** Полный новый набор уровней; разделы с {@link AccessLevel#NONE} отбрасываются. */
    void replaceGrants(Map<Section, AccessLevel> grants) {
        this.grants.clear();
        grants.forEach((section, level) -> {
            if (level != AccessLevel.NONE) {
                this.grants.put(section, level);
            }
        });
    }

    UUID id() {
        return id;
    }

    String code() {
        return code;
    }

    String title() {
        return title;
    }

    boolean builtIn() {
        return builtIn;
    }

    /** Копия: отсутствующий раздел — {@link AccessLevel#NONE}. */
    Map<Section, AccessLevel> grants() {
        return Map.copyOf(grants);
    }

    RoleView toView() {
        return new RoleView(id, code, title, builtIn, grants());
    }
}
