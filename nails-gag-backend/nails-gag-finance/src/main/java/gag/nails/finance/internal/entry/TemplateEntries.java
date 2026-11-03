package gag.nails.finance.internal.entry;

import gag.nails.common.id.UuidV7;
import gag.nails.finance.model.EntryKind;
import gag.nails.finance.model.EntrySnapshot;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Записи, которые создаёт повторяющийся шаблон. Запись — обычная ручная запись с отметкой шаблона: та же таблица,
 * тот же журнал (создание от имени системы — без аккаунта). Только внутри транзакции шаблона ({@code MANDATORY}):
 * запись, её строка журнала и сдвиг отметки шаблона фиксируются вместе.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class TemplateEntries {

    /**
     * Вставка без сбоя на занятом дне: за этот день у шаблона уже есть запись (мастер перенёс на него запись шаблона,
     * или запись создал параллельный запуск) — новая не нужна. Последняя защита от второй записи шаблона за день —
     * уникальность {@code (recurring_id, occurred_on)}.
     */
    private static final String INSERT = """
            insert into finance.entry (id, kind, category_id, amount, occurred_on, comment, recurring_id,
                                       created_at, updated_at)
            values (:id, :kind, :categoryId, :amount, :day, cast(:comment as text), :recurringId, :now, :now)
            on conflict (recurring_id, occurred_on) do nothing""";

    private final EntityManager entityManager;
    private final EntryJournal journal;

    TemplateEntries(EntityManager entityManager, EntryJournal journal) {
        this.entityManager = entityManager;
        this.journal = journal;
    }

    /**
     * Создаёт запись шаблона за день и пишет её в журнал от имени системы.
     *
     * @param categoryName текущее название категории — для снимка в журнале
     * @return {@code true}, если запись создана; {@code false}, если за этот день у шаблона запись уже есть
     */
    public boolean create(
            UUID recurringId,
            EntryKind kind,
            UUID categoryId,
            String categoryName,
            BigDecimal amount,
            @Nullable String comment,
            LocalDate day,
            Instant now) {
        // Идентификатор — UUIDv7, как у записей, которые сохраняет Hibernate: вставка идёт мимо него ради ON CONFLICT.
        var id = UuidV7.next();
        var inserted = entityManager
                .createNativeQuery(INSERT)
                .setParameter("id", id)
                .setParameter("kind", kind.name())
                .setParameter("categoryId", categoryId)
                .setParameter("amount", amount)
                .setParameter("day", day)
                .setParameter("comment", comment)
                .setParameter("recurringId", recurringId)
                .setParameter("now", now)
                .executeUpdate();
        if (inserted == 0) {
            return false;
        }
        var snapshot = new EntrySnapshot(kind, categoryId, categoryName, amount, day, comment, recurringId);
        journal.created(id, snapshot, null, now);
        return true;
    }
}
