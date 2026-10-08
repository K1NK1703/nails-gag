package gag.nails.identity.model;

import gag.nails.common.actor.AccessLevel;
import gag.nails.common.actor.Section;
import java.util.Map;
import java.util.UUID;

/**
 * Роль: набор уровней по разделам.
 *
 * @param grants разделы с уровнем выше {@link AccessLevel#NONE}
 */
public record RoleView(UUID id, String code, String title, boolean builtIn, Map<Section, AccessLevel> grants) {}
