package gag.nails.identity.internal;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code nails-gag.identity.*}. Публичный внутри модуля: нужен подпакетам {@code internal} (провайдеры).
 * Форму значений при старте проверяет {@link IdentityPropertiesGuard}.
 *
 * @param bootstrapDevelopers пары {@code PROVIDER:subject}, которым при входе выдаётся роль
 *     «Разработчик» (например, {@code YANDEX:1234567})
 * @param jobs фоновая задача обслуживания; в тестах выключена
 * @param fakeProvider подставной провайдер; при профиле {@code prod} включать нельзя
 * @param providers настройки Яндекс ID и VK ID; пустой {@code client-id} — провайдер выключен
 */
@ConfigurationProperties("nails-gag.identity")
public record IdentityProperties(
        @DefaultValue List<String> bootstrapDevelopers,
        @DefaultValue Jobs jobs,
        @DefaultValue FakeProviderSettings fakeProvider,
        @DefaultValue Providers providers) {

    public record Jobs(@DefaultValue("true") boolean enabled) {}

    public record FakeProviderSettings(
            @DefaultValue("false") boolean enabled) {}

    public record Providers(
            @DefaultValue ProviderSettings yandex,
            @DefaultValue ProviderSettings vk) {}

    /**
     * Данные приложения у провайдера. Секрет не логировать: {@link #toString()} его не показывает.
     *
     * @param clientId идентификатор приложения; пустой — провайдер выключен
     * @param clientSecret секрет приложения; Яндексу обязателен, VK ID — только конфиденциальному
     *     приложению
     * @param redirectUri абсолютный адрес возврата, {@code https} (для разработки — {@code
     *     http://localhost})
     */
    public record ProviderSettings(
            @DefaultValue("") String clientId,
            @DefaultValue("") String clientSecret,
            @DefaultValue("") String redirectUri) {

        /** Провайдер настроен: есть идентификатор и адрес возврата. Нужен ли секрет — решает провайдер. */
        public boolean enabled() {
            return !clientId.isBlank() && !redirectUri.isBlank();
        }

        @Override
        public String toString() {
            return "ProviderSettings[clientId=" + clientId + ", clientSecret="
                    + (clientSecret.isBlank() ? "<empty>" : "<set>") + ", redirectUri=" + redirectUri + "]";
        }
    }
}
