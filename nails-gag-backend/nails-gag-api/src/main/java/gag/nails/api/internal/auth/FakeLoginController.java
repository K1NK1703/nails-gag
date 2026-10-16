package gag.nails.api.internal.auth;

import gag.nails.identity.testing.FakeProvider;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Код подставного провайдера по HTTP (спецификация API, §6.2): вход без Яндекса и VK — {@code start(fake)} →
 * {@code code} → обратный вызов. Адрес есть только вместе с подставным провайдером
 * ({@code nails-gag.identity.fake-provider.enabled=true}: локально и в тестах); в {@code prod} эту настройку
 * не пропускает страж модуля входа.
 */
@RestController
@RequestMapping("/api/auth/fake")
@ConditionalOnProperty(name = "nails-gag.identity.fake-provider.enabled", havingValue = "true")
class FakeLoginController {

    private final FakeProvider fakeProvider;

    FakeLoginController(FakeProvider fakeProvider) {
        this.fakeProvider = fakeProvider;
    }

    @PostMapping("/code")
    FakeCode code(@Valid @RequestBody FakeLogin body) {
        return new FakeCode(fakeProvider.issueCode(body.profile()));
    }
}
