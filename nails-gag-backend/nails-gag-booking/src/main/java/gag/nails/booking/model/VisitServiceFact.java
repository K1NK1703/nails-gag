package gag.nails.booking.model;

import gag.nails.catalog.model.ServiceKind;
import java.math.BigDecimal;
import java.util.UUID;

/** Услуга фактического состава выполненного визита: снимок названия и цены на момент отметки или правки визита. */
public record VisitServiceFact(UUID serviceId, ServiceKind kind, String title, BigDecimal price) {}
