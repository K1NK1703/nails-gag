package gag.nails.identity.model;

import gag.nails.identity.LoginFlow;

/**
 * Аккаунта ещё нет: нужен экран регистрации с согласием на обработку данных. {@code ticket} —
 * одноразовый билет на 30 минут для {@link LoginFlow#completeRegistration}.
 */
public record RegistrationRequired(String ticket, RegistrationPrefill prefill) implements LoginResult {}
