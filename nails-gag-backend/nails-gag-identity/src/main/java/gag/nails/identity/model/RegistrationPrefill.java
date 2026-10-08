package gag.nails.identity.model;

import org.jspecify.annotations.Nullable;

/**
 * Подстановка на экран регистрации: имя и телефон от провайдера или от найденной карточки.
 *
 * @param phoneVerified телефон подтверждён провайдером (тогда он попадёт в карточку как доверенный)
 */
public record RegistrationPrefill(
        @Nullable String displayName, @Nullable String phone, boolean phoneVerified) {}
