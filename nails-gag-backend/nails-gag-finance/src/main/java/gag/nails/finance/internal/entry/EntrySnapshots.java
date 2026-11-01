package gag.nails.finance.internal.entry;

import com.fasterxml.jackson.annotation.JsonInclude;
import gag.nails.common.error.InternalException;
import gag.nails.finance.model.EntrySnapshot;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Снимки записи в журнале — JSON в {@code jsonb}. Свой {@link JsonMapper} с явными настройками: формат хранения не
 * зависит от настроек JSON приложения и HTTP-слоя, которые могут меняться ради представления. Формат: поля
 * {@link EntrySnapshot} по именам компонентов, пустые — явный {@code null}; день — {@code "2026-10-01"}; сумма —
 * число без экспоненты с копейками ({@code 500.00}). Имена компонентов — формат хранения: их переименование требует
 * миграции журнала. Незнакомые поля при чтении пропускаются: удалённый когда-нибудь компонент не ломает старую
 * историю.
 */
@Component
class EntrySnapshots {

    private final JsonMapper json = JsonMapper.builder()
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .changeDefaultPropertyInclusion(inclusion -> inclusion.withValueInclusion(JsonInclude.Include.ALWAYS))
            .build();

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
