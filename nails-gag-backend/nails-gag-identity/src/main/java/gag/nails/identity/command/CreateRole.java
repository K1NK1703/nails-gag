package gag.nails.identity.command;

import gag.nails.common.actor.AccessLevel;
import gag.nails.common.actor.Section;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * Новая роль из конструктора ролей.
 *
 * @param code постоянный код: латинские заглавные буквы, цифры и подчёркивание, 2–64 символа
 * @param grants уровни по разделам; разделы с {@link AccessLevel#NONE} отбрасываются
 */
public record CreateRole(
        @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{1,63}") String code,
        @NotBlank @Size(max = 100) String title,
        @NotNull Map<Section, AccessLevel> grants) {}
