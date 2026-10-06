package gag.nails.identity.internal;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code nails-gag.identity.*}. Публичный внутри модуля: нужен подпакетам {@code internal} (провайдеры).
 *
 * @param bootstrapDevelopers пары {@code provider:subject}, которым при входе выдаётся роль
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

    /** Данные приложения у провайдера. Секрет не логировать. */
    public record ProviderSettings(
            @DefaultValue("") String clientId,
            @DefaultValue("") String clientSecret,
            @DefaultValue("") String redirectUri) {

        /** Провайдер настроен и включён. */
        public boolean enabled() {
            return !clientId.isBlank();
        }
    }
}
