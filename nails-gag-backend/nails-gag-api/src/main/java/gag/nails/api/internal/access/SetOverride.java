package gag.nails.api.internal.access;

import gag.nails.common.actor.AccessLevel;
import jakarta.validation.constraints.NotNull;

/**
 * Тело {@code PUT /api/access/accounts/{id}/overrides/{section}}: личное переопределение уровня раздела.
 *
 * @param level уровень, в том числе явный {@link AccessLevel#NONE}; без уровня — {@code 400 common.validation} с полем
 *     {@code level}: снимает переопределение {@code DELETE} того же адреса
 */
record SetOverride(@NotNull AccessLevel level) {}
