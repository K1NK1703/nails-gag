package gag.nails.booking.internal.listeners;

import gag.nails.booking.internal.booking.BookingEntity;
import gag.nails.booking.internal.booking.BookingRepository;
import gag.nails.booking.internal.booking.ClientLocks;
import gag.nails.booking.internal.booking.CurrentRules;
import gag.nails.booking.internal.reference.ReferenceRemoval;
import gag.nails.booking.model.CancelReason;
import gag.nails.clients.event.ClientErased;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Обезличивание клиента. Записи не отменяются: клиентка могла удалить аккаунт, но прийти по записи (спецификация
 * финансов §3.3а). Активные записи, визит по которым ещё не начался (начало визита — согласованное более раннее
 * время, если клиент согласился прийти раньше, иначе начало слота), остаются активными со своим слотом и сроком
 * предоплаты и получают отметку обезличивания — они на рассмотрении у мастера: оставить или отменить (отмена
 * мастером такой записи идёт с причиной {@link CancelReason#ACCOUNT_DELETED}). Записи, визит по которым уже начался,
 * ждут отметки, как любые другие; завершённые и отменённые не меняются. Комментарий клиента и причина итоговой суммы
 * стираются, а фото-референсы (с файлами; файлы на диске — после фиксации) удаляются у всех его записей, кто бы их ни
 * приложил: там могут быть данные клиента. Уведомлений нет — контактов у обезличенного клиента не осталось. Обычный
 * {@code @EventListener} — выполняется синхронно в транзакции обезличивания, как требует {@link ClientErased}.
 *
 * <p>Сначала берётся блокировка клиента: параллельная самозапись либо уже зафиксирована и попадёт в выборку, либо
 * дождётся конца обезличивания и увидит обезличенную карточку. Затем записи читаются под блокировкой строк:
 * параллельная отмена, перенос или отметка той же записи идут строго до или после, а параллельное добавление
 * референса либо уже зафиксировано, и референс удаляется здесь, либо дождётся конца обезличивания и откажет: к записям
 * обезличенного клиента референсы не прикладывает никто. Слоты обезличивание больше не трогает.
 *
 * <p>Известное ограничение: обезличивание уже держит строку клиента и только потом берёт записи (клиент → запись),
 * тогда как поздняя отмена, отметки визита и перенос записи клиентом идут в порядке запись → клиент. Если обезличивание
 * совпадёт по времени с такой операцией над записью того же клиента, PostgreSQL обнаружит взаимную блокировку и
 * прервёт одну из транзакций. Принято из-за редкости: удаление аккаунта — ручное действие клиента, прерванную операцию
 * достаточно повторить.
 */
@Component
class ClientEraseListener {

    private final BookingRepository bookings;
    private final ClientLocks locks;
    private final CurrentRules currentRules;
    private final ReferenceRemoval references;

    ClientEraseListener(
            BookingRepository bookings, ClientLocks locks, CurrentRules currentRules, ReferenceRemoval references) {
        this.bookings = bookings;
        this.locks = locks;
        this.currentRules = currentRules;
        this.references = references;
    }

    @EventListener
    void on(ClientErased event) {
        locks.acquire(event.clientId());
        var rules = currentRules.get();
        var locked = bookings.findAllLockedByClientId(event.clientId());
        for (var booking : locked) {
            if (booking.status().isActive() && !rules.visitStarted(booking.visitStart())) {
                booking.markClientErased(rules.now());
            }
            booking.wipeFreeTexts();
        }
        references.removeAll(locked.stream().map(BookingEntity::id).toList());
    }
}
