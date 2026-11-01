package gag.nails.finance.internal.entry;

import gag.nails.common.error.InternalException;
import gag.nails.finance.model.EntrySnapshot;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Снимки записи в журнале — JSON через {@link JsonMapper} приложения: поля {@link EntrySnapshot} по именам
 * компонентов, день — {@code "2026-10-01"}, сумма — число с копейками. Имена компонентов — формат хранения: их
 * переименование требует миграции журнала.
 */
@Component
class EntrySnapshots {

    private final JsonMapper json;

    EntrySnapshots(JsonMapper json) {
        this.json = json;
    }

    @Nullable
    String write(@Nullable EntrySnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        try {
            return json.writeValueAsString(snapshot);
        } catch (JacksonException e) {
            throw new InternalException("entry snapshot is not writable as JSON", e);
        }
    }

    @Nullable
    EntrySnapshot read(@Nullable String snapshot) {
        if (snapshot == null) {
            return null;
        }
        try {
            return json.readValue(snapshot, EntrySnapshot.class);
        } catch (JacksonException e) {
            throw new InternalException("entry snapshot in the journal is not readable", e);
        }
    }
}
