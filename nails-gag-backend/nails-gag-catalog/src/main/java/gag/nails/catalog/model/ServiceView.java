package gag.nails.catalog.model;

import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Услуга каталога.
 *
 * @param durationMinutes справочная длительность; есть только у основных услуг, у дополнений {@code null}
 */
public record ServiceView(
        UUID id,
        ServiceKind kind,
        String title,
        String description,
        BigDecimal price,
        @Nullable Integer durationMinutes,
        String category,
        int sortOrder,
        boolean showOnLanding,
        boolean highlightOnLanding,
        boolean active) {}
