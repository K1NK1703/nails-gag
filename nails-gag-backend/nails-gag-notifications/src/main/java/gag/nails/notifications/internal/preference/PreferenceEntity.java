package gag.nails.notifications.internal.preference;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Своя настройка каналов аккаунта; пишется только вставкой с {@code ON CONFLICT} ({@link PreferenceRepository}). */
@Entity
@Table(schema = "notifications", name = "preference")
class PreferenceEntity {

    @Id
    private UUID id;

    private UUID accountId;
    private boolean emailEnabled;
    private boolean telegramEnabled;
    private Instant updatedAt;

    protected PreferenceEntity() {}

    ChannelSwitches switches() {
        return new ChannelSwitches(emailEnabled, telegramEnabled);
    }
}
