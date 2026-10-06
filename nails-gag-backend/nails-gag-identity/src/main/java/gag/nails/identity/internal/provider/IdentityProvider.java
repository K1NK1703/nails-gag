package gag.nails.identity.internal.provider;

import gag.nails.identity.AuthorizationResponse;
import gag.nails.identity.Provider;
import gag.nails.identity.error.LoginFailedException;
import gag.nails.identity.error.ProviderUnavailableException;
import java.net.URI;

/**
 * Порт провайдера входа (спецификация 4.2). Реализации — Яндекс ID, VK ID и подставной провайдер для
 * тестов. Обмен кода на профиль идёт с сервера; токены провайдера дальше {@link #exchange} не живут:
 * не сохраняются и не логируются. Публичный внутри модуля: нужен {@code internal} (вход).
 */
public interface IdentityProvider {

    Provider id();

    /** Провайдер настроен: у настоящих — задан {@code client-id}, у подставного — всегда. */
    boolean enabled();

    /**
     * Адрес, куда отправить пользователя. {@code state} провайдер вернёт без изменений; {@code
     * codeChallenge} — {@code base64url(SHA-256(codeVerifier))}, метод {@code S256}.
     */
    URI authorizationUri(String state, String codeChallenge, URI redirectUri);

    /**
     * Меняет код из ответа провайдера на профиль пользователя. Телефон и почта в профиле уже
     * нормализованы (E.164, нижний регистр) и считаются подтверждёнными провайдером.
     *
     * @throws LoginFailedException провайдер отверг код или ответ провайдера не содержит нужного
     * @throws ProviderUnavailableException сеть, таймаут, ответ {@code 5xx} или нечитаемый ответ
     */
    ProviderProfile exchange(AuthorizationResponse response, String codeVerifier, URI redirectUri);
}
