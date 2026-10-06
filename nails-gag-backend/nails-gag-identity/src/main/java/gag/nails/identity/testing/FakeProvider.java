package gag.nails.identity.testing;

import gag.nails.identity.AuthorizationResponse;
import gag.nails.identity.Provider;

/**
 * Подставной провайдер ({@link Provider#FAKE}): тесты и локальная разработка. Бин есть только при
 * {@code nails-gag.identity.fake-provider.enabled=true}; при профиле {@code prod} с этой настройкой
 * приложение не стартует. Единственный способ пройти вход через публичный API без сети.
 */
public interface FakeProvider {

    /** Регистрирует профиль и возвращает одноразовый {@code code} для {@link AuthorizationResponse}. */
    String issueCode(FakeProfile profile);

    /** Следующий {@code complete} с этим провайдером упадёт как недоступность провайдера. */
    void failNextExchange();
}
