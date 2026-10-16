package gag.nails.api.internal.auth;

import gag.nails.identity.testing.FakeProfile;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * Тело {@code POST /api/auth/fake/code} — {@link FakeProfile} с ограничениями формы: поля и JSON те же, длины — как
 * у способа входа в базе.
 *
 * @param subject идентификатор человека у подставного провайдера; один {@code subject} — один человек
 */
record FakeLogin(
        @NotBlank @Size(max = 128) String subject,
        @Nullable @Size(max = 32) String phone,
        @Nullable @Email @Size(max = 254) String email,
        @Nullable @Size(max = 200) String displayName,
        @Nullable @Size(max = 1024) String avatarUrl) {

    FakeProfile profile() {
        return new FakeProfile(subject, phone, email, displayName, avatarUrl);
    }

    /** Телефон и почта — личные данные, в логах их не должно быть. */
    @Override
    public String toString() {
        return "FakeLogin[subject=" + subject + ", phone=<hidden>, email=<hidden>]";
    }
}
