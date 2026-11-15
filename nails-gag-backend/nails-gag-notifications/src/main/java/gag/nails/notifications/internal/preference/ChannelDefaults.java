package gag.nails.notifications.internal.preference;

import gag.nails.common.actor.AccessLevel;
import gag.nails.common.actor.Section;
import gag.nails.identity.StaffDirectory;
import gag.nails.identity.model.StaffRecipient;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Каналы получателя без своей настройки (нет строки {@code preference}), спека §3.2.
 *
 * <p>Умолчания — свойство аккаунта, а не сообщения: и в кабинете, и при отправке любого его сообщения действует одно и
 * то же. Сотрудник здесь — тот, кто получает мастерские сообщения, то есть входит в
 * {@code StaffDirectory.recipients(BOOKINGS, READ)} (незаблокированный, итоговый уровень раздела «Записи» не ниже
 * чтения). С ролью {@code MASTER} у него включён только Telegram, без неё (например, только разработчик) — ничего.
 * Прочие аккаунты и карточки без аккаунта — клиентки: почта и Telegram.
 */
@Component
public class ChannelDefaults {

    /** Раздел и уровень, с которыми аккаунт получает мастерские сообщения. */
    public static final Section STAFF_SECTION = Section.BOOKINGS;

    public static final AccessLevel STAFF_LEVEL = AccessLevel.READ;

    /** Код встроенной роли «Мастер». */
    public static final String MASTER_ROLE = "MASTER";

    public static final ChannelSwitches CLIENT = new ChannelSwitches(true, true);
    public static final ChannelSwitches MASTER = new ChannelSwitches(false, true);
    public static final ChannelSwitches OTHER_STAFF = new ChannelSwitches(false, false);

    private final StaffDirectory staffDirectory;

    ChannelDefaults(StaffDirectory staffDirectory) {
        this.staffDirectory = staffDirectory;
    }

    /** Умолчания аккаунта: сотрудника — по его ролям, иначе клиентские. */
    public ChannelSwitches forAccount(UUID accountId) {
        return staffDirectory.recipients(STAFF_SECTION, STAFF_LEVEL).stream()
                .filter(recipient -> recipient.accountId().equals(accountId))
                .findFirst()
                .map(StaffRecipient::roleCodes)
                .map(ChannelDefaults::forStaff)
                .orElse(CLIENT);
    }

    /** Умолчания сотрудника-получателя мастерских сообщений по кодам его ролей. */
    public static ChannelSwitches forStaff(Set<String> roleCodes) {
        return roleCodes.contains(MASTER_ROLE) ? MASTER : OTHER_STAFF;
    }
}
