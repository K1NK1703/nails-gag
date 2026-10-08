package gag.nails.booking.model;

import gag.nails.catalog.model.ServiceKind;
import java.math.BigDecimal;
import java.util.UUID;

/** Услуга в составе записи: снимок названия и цены на момент записи (или отметки визита). */
public record BookedServiceView(UUID serviceId, ServiceKind kind, String title, BigDecimal price) {}
