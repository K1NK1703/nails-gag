package gag.nails.identity.command;

import gag.nails.common.actor.AccessLevel;
import gag.nails.common.actor.Section;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * Изменение роли: название и уровни; код не меняется.
 *
 * @param grants полный новый набор уровней; разделы с {@link AccessLevel#NONE} отбрасываются
 */
public record UpdateRole(
        @NotBlank @Size(max = 100) String title, @NotNull Map<Section, AccessLevel> grants) {}
