package gag.nails.identity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Данные с экрана регистрации.
 *
 * @param phone телефон в любом привычном написании; если он отличается от подтверждённого провайдером
 *     или подтверждённого нет — становится номером для связи карточки
 * @param policyVersion версия политики обработки данных, показанная под галочкой согласия
 */
public record CompleteRegistration(
        @NotBlank @Size(max = 100) String firstName,
        @NotBlank @Size(max = 32) String phone,
        @NotBlank @Size(max = 64) String policyVersion) {}
