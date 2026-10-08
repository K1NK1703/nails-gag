package gag.nails.identity.internal.login;

import gag.nails.identity.error.RegistrationExpiredException;
import gag.nails.identity.internal.provider.ProviderProfile;
import gag.nails.identity.internal.session.OpaqueTokens;
import gag.nails.identity.model.Provider;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Билеты незавершённой регистрации и привязки: 32 случайных байта, в базе только SHA-256, живут 30
 * минут, одноразовые. Профиль провайдера до согласия на обработку данных лежит только здесь.
 */
@Component
public class PendingRegistrations {

    public static final Duration TTL = Duration.ofMinutes(30);

    private final PendingRegistrationRepository pending;
    private final Clock clock;

    PendingRegistrations(PendingRegistrationRepository pending, Clock clock) {
        this.pending = pending;
        this.clock = clock;
    }

    /** Билет на завершение регистрации; {@code clientId} — карточка без аккаунта, к которой привяжемся. */
    String issueRegistration(Provider provider, ProviderProfile profile, @Nullable UUID clientId) {
        var ticket = OpaqueTokens.token();
        pending.save(PendingRegistrationEntity.register(
                OpaqueTokens.hash(ticket),
                provider,
                profile.subject(),
                profile.phone(),
                profile.email(),
                profile.displayName(),
                profile.avatarUrl(),
                clientId,
                clock.instant()));
        return ticket;
    }

    /** Билет на подтверждение объединения: способ входа принадлежит {@code otherAccountId}. */
    String issueLink(Provider provider, ProviderProfile profile, UUID accountId, UUID otherAccountId) {
        var ticket = OpaqueTokens.token();
        pending.save(PendingRegistrationEntity.link(
                OpaqueTokens.hash(ticket),
                provider,
                profile.subject(),
                profile.phone(),
                profile.email(),
                profile.displayName(),
                profile.avatarUrl(),
                accountId,
                otherAccountId,
                clock.instant()));
        return ticket;
    }

    /**
     * Находит и удаляет билет: второй раз его не предъявить. Удаление — одним запросом с проверкой числа
     * строк: два одновременных предъявления одного билета оба его находят, но удалит только одно, второе
     * получит ту же ошибку, что и просроченный билет, а не сбой.
     *
     * @throws RegistrationExpiredException билета нет, он выдан для другой цели, старше 30 минут или уже
     *     использован
     */
    PendingRegistrationEntity consume(String ticket, PendingPurpose purpose) {
        var hash = OpaqueTokens.hash(ticket);
        var found = pending.findByTicketHash(hash).orElseThrow(RegistrationExpiredException::new);
        if (found.purpose() != purpose || found.createdAt().plus(TTL).isBefore(clock.instant())) {
            throw new RegistrationExpiredException();
        }
        if (pending.deleteByTicketHash(hash) == 0) {
            throw new RegistrationExpiredException();
        }
        return found;
    }

    static ProviderProfile profileOf(PendingRegistrationEntity entity) {
        return new ProviderProfile(
                entity.subject(), entity.phone(), entity.email(), entity.displayName(), entity.avatarUrl());
    }
}
