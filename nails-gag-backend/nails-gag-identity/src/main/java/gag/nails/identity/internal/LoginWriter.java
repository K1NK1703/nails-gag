package gag.nails.identity.internal;

import gag.nails.clients.ClientQueries;
import gag.nails.clients.ClientService;
import gag.nails.clients.DocumentVersionService;
import gag.nails.clients.command.RecordConsent;
import gag.nails.clients.command.RegisterClient;
import gag.nails.clients.error.ConsentVersionOutdatedException;
import gag.nails.clients.model.ClientView;
import gag.nails.clients.model.ConsentKind;
import gag.nails.clients.model.ConsentSource;
import gag.nails.clients.model.DocumentKind;
import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.error.InternalException;
import gag.nails.common.text.Phones;
import gag.nails.common.validation.CommandValidator;
import gag.nails.identity.ClientInfo;
import gag.nails.identity.CompleteRegistration;
import gag.nails.identity.LinkConfirmationRequired;
import gag.nails.identity.Linked;
import gag.nails.identity.LoginOutcome;
import gag.nails.identity.LoginPurpose;
import gag.nails.identity.LoginResult;
import gag.nails.identity.LoginState;
import gag.nails.identity.Provider;
import gag.nails.identity.RegistrationPrefill;
import gag.nails.identity.RegistrationRequired;
import gag.nails.identity.SignedIn;
import gag.nails.identity.error.AccountBlockedException;
import gag.nails.identity.error.LoginFailedException;
import gag.nails.identity.error.RegistrationExpiredException;
import gag.nails.identity.internal.LoginMatcher.KnownAccount;
import gag.nails.identity.internal.LoginMatcher.Nobody;
import gag.nails.identity.internal.LoginMatcher.UnregisteredCard;
import gag.nails.identity.internal.provider.ProviderProfile;
import java.time.Clock;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Транзакционная часть входа: всё, что идёт после обмена кода на профиль. {@link DefaultLoginFlow}
 * зовёт эти методы без внешней транзакции, поэтому каждый — своя короткая транзакция: соединение из пула
 * занято только на запись, а не на ожидание провайдера.
 *
 * <p>Исходы «вход не удался» и «карточка заблокирована» попадают в журнал входов вместе с исключением:
 * транзакция для них не откатывается — к этому моменту в ней нет других изменений, кроме потреблённого
 * билета, который заблокированному всё равно бесполезен.
 *
 * <p>Одновременные входы одного человека сериализуются {@link LoginLocks}: {@link #complete},
 * {@link #completeRegistration} и {@link #confirmLink} берут одни и те же блокировки, и завершение
 * регистрации заново сопоставляет профиль — если за это время такой же человек уже вошёл (параллельная
 * регистрация, карточка с его телефоном), регистрация завершается входом в найденный аккаунт, а не
 * вторым аккаунтом.
 *
 * <p>Порядок блокировок строк: advisory-блокировки входа → карточка клиента (подтверждённый номер,
 * согласие, объединение) → роль {@code DEVELOPER} (только у {@link #confirmLink}, через объединение
 * карточек) — тот же, что у блокировки клиента мастером. Изменения прав держат роль {@code DEVELOPER} и
 * карточки лишь читают, так что с ними взаимной блокировки нет.
 */
@Component
@Transactional
class LoginWriter {

    private final LoginLocks locks;
    private final LoginMatcher matcher;
    private final PendingRegistrations pendings;
    private final AccountRepository accounts;
    private final LoginMethodRepository loginMethods;
    private final SessionRepository sessions;
    private final SessionOpener sessionOpener;
    private final Bootstrap bootstrap;
    private final AccountMerger accountMerger;
    private final AccessGuard guard;
    private final LoginEvents events;
    private final ClientService clientService;
    private final ClientQueries clientQueries;
    private final DocumentVersionService documents;
    private final CommandValidator validator;
    private final Clock clock;

    LoginWriter(
            LoginLocks locks,
            LoginMatcher matcher,
            PendingRegistrations pendings,
            AccountRepository accounts,
            LoginMethodRepository loginMethods,
            SessionRepository sessions,
            SessionOpener sessionOpener,
            Bootstrap bootstrap,
            AccountMerger accountMerger,
            AccessGuard guard,
            LoginEvents events,
            ClientService clientService,
            ClientQueries clientQueries,
            DocumentVersionService documents,
            CommandValidator validator,
            Clock clock) {
        this.locks = locks;
        this.matcher = matcher;
        this.pendings = pendings;
        this.accounts = accounts;
        this.loginMethods = loginMethods;
        this.sessions = sessions;
        this.sessionOpener = sessionOpener;
        this.bootstrap = bootstrap;
        this.accountMerger = accountMerger;
        this.guard = guard;
        this.events = events;
        this.clientService = clientService;
        this.clientQueries = clientQueries;
        this.documents = documents;
        this.validator = validator;
        this.clock = clock;
    }

    /** Исход без аккаунта и без других записей: своя короткая транзакция, чтобы строка журнала осталась. */
    void journalFailure(Provider provider, ClientInfo client) {
        events.record(null, provider, LoginOutcome.FAILED, client);
    }

    /** Возврат от провайдера после успешного обмена кода на профиль. */
    @Transactional(noRollbackFor = {LoginFailedException.class, AccountBlockedException.class})
    LoginResult complete(Provider provider, ProviderProfile profile, LoginState state, ClientInfo client) {
        locks.acquire(provider, profile);
        return state.purpose() == LoginPurpose.LINK
                ? link(state.accountId(), provider, profile, client)
                : login(provider, profile, client);
    }

    /** Открытая сессия и провайдер способа входа — для проверки блокировки после фиксации. */
    record Opened(SignedIn signedIn, Provider provider) {}

    @Transactional(noRollbackFor = AccountBlockedException.class)
    Opened completeRegistration(String ticket, CompleteRegistration command, ClientInfo client) {
        validator.requireValid(command);
        var contactPhone = Phones.require(command.phone());
        var pending = pendings.consume(ticket, PendingPurpose.REGISTER);
        requireCurrentPolicy(command.policyVersion());
        var provider = pending.provider();
        var profile = PendingRegistrations.profileOf(pending);
        locks.acquire(provider, profile);
        // Пока человек заполнял форму, он мог войти другим способом или мастер мог завести его карточку.
        var signedIn = switch (matcher.match(provider, profile)) {
            case KnownAccount known -> signIn(known, provider, profile, client);
            case UnregisteredCard card -> register(provider, profile, card.card(), command, contactPhone, client);
            case Nobody nobody -> register(provider, profile, null, command, contactPhone, client);
        };
        return new Opened(signedIn, provider);
    }

    /**
     * Подтверждение объединения. Билет привязан к аккаунту, который его получил; способ входа должен
     * всё ещё принадлежать тому же другому аккаунту (ничей — просто привязывается). У аккаунта вошедшего
     * должна быть карточка, если она есть у другого: иначе объединять нечего, а карточка другого
     * осталась бы указывать на удалённый аккаунт — такой билет отклоняется как неудавшийся вход
     * ({@link LoginFailedException}, в журнале {@code FAILED}).
     */
    @Transactional(noRollbackFor = {LoginFailedException.class, AccountBlockedException.class})
    Linked confirmLink(String ticket, Actor actor, ClientInfo client) {
        var accountId = requireAccount(actor);
        var pending = pendings.consume(ticket, PendingPurpose.LINK);
        var otherAccountId = pending.otherAccountId();
        if (!accountId.equals(pending.accountId()) || otherAccountId == null) {
            throw new RegistrationExpiredException();
        }
        var provider = pending.provider();
        var profile = PendingRegistrations.profileOf(pending);
        locks.acquire(provider, profile);
        var own = clientQueries.byAccountId(accountId);
        var other = clientQueries.byAccountId(otherAccountId);
        own.ifPresent(card -> requireNotBlocked(card, accountId, provider, client));
        other.ifPresent(card -> requireNotBlocked(card, otherAccountId, provider, client));
        var method = loginMethods.findByProviderAndSubject(provider, profile.subject());
        if (method.isEmpty()) {
            // Пока ждали подтверждения, другой аккаунт отвязал этот способ входа: он ничей, просто привязываем.
            loginMethods.save(newMethod(accountId, provider, profile));
        } else if (!method.get().accountId().equals(accountId)) {
            if (!method.get().accountId().equals(otherAccountId)) {
                throw new RegistrationExpiredException();
            }
            if (own.isEmpty() && other.isPresent()) {
                throw failed(provider, accountId, client);
            }
            if (other.isPresent()) {
                // ClientMerged обработает ClientMergedListener: перенесёт способы входа и права, удалит аккаунт.
                clientService.mergeOwn(other.get().id(), actor);
            } else {
                accountMerger.merge(otherAccountId, accountId);
                guard.requireAdminNotLost();
            }
        }
        events.record(accountId, provider, LoginOutcome.LINKED, client);
        return new Linked(accountId);
    }

    /**
     * Гонка входа с блокировкой: карточка перечитывается уже после фиксации сессии, в своей транзакции и
     * под блокировкой строки аккаунта — той же, что берёт слушатель {@code ClientBlocked}. Либо блокировка
     * уже зафиксирована и видна здесь (сессии закрываются, вход отклоняется), либо слушатель ждёт этой
     * транзакции и затем видит сессию сам. Аккаунта уже нет — проверять нечего.
     */
    @Transactional(noRollbackFor = AccountBlockedException.class)
    void rejectIfBlocked(SignedIn signedIn, Provider provider, ClientInfo client) {
        if (accounts.lockById(signedIn.accountId()).isEmpty()) {
            return;
        }
        var blocked = clientQueries
                .byAccountId(signedIn.accountId())
                .filter(ClientView::blocked)
                .isPresent();
        if (blocked) {
            sessions.deleteAllByAccountId(signedIn.accountId());
            events.record(signedIn.accountId(), provider, LoginOutcome.BLOCKED, client);
            throw new AccountBlockedException();
        }
    }

    private LoginResult login(Provider provider, ProviderProfile profile, ClientInfo client) {
        return switch (matcher.match(provider, profile)) {
            case KnownAccount known -> signIn(known, provider, profile, client);
            case UnregisteredCard found -> {
                var card = found.card();
                requireNotBlocked(card, null, provider, client);
                yield registrationRequired(provider, profile, card);
            }
            case Nobody nobody -> registrationRequired(provider, profile, null);
        };
    }

    private RegistrationRequired registrationRequired(
            Provider provider, ProviderProfile profile, @Nullable ClientView card) {
        var ticket = pendings.issueRegistration(provider, profile, card == null ? null : card.id());
        var phone = profile.phone() != null ? profile.phone() : card == null ? null : card.phone();
        return new RegistrationRequired(
                ticket, new RegistrationPrefill(profile.displayName(), phone, profile.phone() != null));
    }

    private LoginResult link(@Nullable UUID accountId, Provider provider, ProviderProfile profile, ClientInfo client) {
        if (accountId == null || accounts.findById(accountId).isEmpty()) {
            throw failed(provider, accountId, client);
        }
        clientQueries.byAccountId(accountId).ifPresent(card -> requireNotBlocked(card, accountId, provider, client));
        var existing = loginMethods.findByProviderAndSubject(provider, profile.subject());
        if (existing.isEmpty()) {
            loginMethods.save(newMethod(accountId, provider, profile));
            events.record(accountId, provider, LoginOutcome.LINKED, client);
            return new Linked(accountId);
        }
        var ownerId = existing.get().accountId();
        if (ownerId.equals(accountId)) {
            events.record(accountId, provider, LoginOutcome.LINKED, client);
            return new Linked(accountId);
        }
        clientQueries.byAccountId(ownerId).ifPresent(card -> requireNotBlocked(card, ownerId, provider, client));
        return new LinkConfirmationRequired(pendings.issueLink(provider, profile, accountId, ownerId));
    }

    /** Вход в найденный аккаунт: способ входа создаётся или обновляется, открывается сессия. */
    private SignedIn signIn(KnownAccount known, Provider provider, ProviderProfile profile, ClientInfo client) {
        var accountId = known.accountId();
        var account = accounts.findById(accountId)
                .orElseThrow(() -> new InternalException(
                        "account " + accountId + " referenced by a login method or card is missing"));
        var card = clientQueries.byAccountId(accountId);
        card.ifPresent(c -> requireNotBlocked(c, accountId, provider, client));
        var now = clock.instant();
        LoginMethodEntity method;
        if (known.method() == null) {
            method = loginMethods.save(newMethod(accountId, provider, profile));
        } else {
            method = known.method();
            method.used(profile.phone(), profile.email(), profile.displayName(), profile.avatarUrl(), now);
        }
        account.loggedIn(now);
        card.ifPresent(c -> confirmPhone(c, profile.phone()));
        return open(account, method, provider, profile, LoginOutcome.SIGNED_IN, client);
    }

    /**
     * Новый аккаунт: карточка привязывается или создаётся самим {@code clients.register} (по телефону и
     * почте провайдера), введённый телефон становится номером для связи, если он не совпал с
     * доверенным номером карточки; согласие записывается на показанную версию политики. Если сопоставление
     * нашло карточку без аккаунта, {@code register} обязан привязаться к ней же — оба ищут одинаково;
     * иное — рассогласование модулей, а не ошибка пользователя.
     */
    private SignedIn register(
            Provider provider,
            ProviderProfile profile,
            @Nullable ClientView expectedCard,
            CompleteRegistration command,
            String contactPhone,
            ClientInfo client) {
        if (expectedCard != null) {
            requireNotBlocked(expectedCard, null, provider, client);
        }
        var account = accounts.save(new AccountEntity(clock.instant()));
        var method = loginMethods.save(newMethod(account.id(), provider, profile));
        var card = clientService.register(
                new RegisterClient(account.id(), command.firstName(), profile.phone(), profile.email()));
        if (expectedCard != null && !card.id().equals(expectedCard.id())) {
            throw new InternalException("registration matched client " + expectedCard.id()
                    + " but clients.register attached account " + account.id() + " to client " + card.id());
        }
        confirmPhone(card, profile.phone());
        if (!contactPhone.equals(card.phone())) {
            clientService.changeContactPhone(card.id(), contactPhone, Actor.client(card.id()));
        }
        clientService.recordConsent(
                card.id(),
                new RecordConsent(ConsentKind.PERSONAL_DATA, command.policyVersion(), ConsentSource.SITE_CHECKBOX),
                Actor.client(card.id()));
        return open(account, method, provider, profile, LoginOutcome.REGISTERED, client);
    }

    private SignedIn open(
            AccountEntity account,
            LoginMethodEntity method,
            Provider provider,
            ProviderProfile profile,
            LoginOutcome outcome,
            ClientInfo client) {
        bootstrap.apply(account.id(), provider, profile.subject());
        var token = sessionOpener.open(account.id(), method.id());
        events.record(account.id(), provider, outcome, client);
        return new SignedIn(token, account.id());
    }

    /**
     * Подтверждённый провайдером номер записывается в карточку, если своего доверенного у неё нет и номер
     * свободен. Проверка занятости здесь, а не ловлей {@code PhoneTakenException}: после нарушения
     * уникального индекса транзакция PostgreSQL прервана.
     */
    private void confirmPhone(ClientView card, @Nullable String phone) {
        if (phone == null
                || card.phone() != null
                || clientQueries.byPhone(phone).isPresent()) {
            return;
        }
        clientService.confirmPhone(card.id(), phone);
    }

    /**
     * Версия политики сверяется до создания аккаунта и карточки: устаревшая версия — ошибка формы, после
     * которой человек просто соглашается заново ({@code clients.recordConsent} проверит её ещё раз).
     */
    private void requireCurrentPolicy(String policyVersion) {
        var current = documents.current(DocumentKind.POLICY).version();
        if (!current.equals(policyVersion)) {
            throw new ConsentVersionOutdatedException(ConsentKind.PERSONAL_DATA, current);
        }
    }

    private void requireNotBlocked(ClientView card, @Nullable UUID accountId, Provider provider, ClientInfo client) {
        if (card.blocked()) {
            events.record(accountId, provider, LoginOutcome.BLOCKED, client);
            throw new AccountBlockedException();
        }
    }

    private LoginFailedException failed(Provider provider, @Nullable UUID accountId, ClientInfo client) {
        events.record(accountId, provider, LoginOutcome.FAILED, client);
        return new LoginFailedException();
    }

    private LoginMethodEntity newMethod(UUID accountId, Provider provider, ProviderProfile profile) {
        return new LoginMethodEntity(
                accountId,
                provider,
                profile.subject(),
                profile.phone(),
                profile.email(),
                profile.displayName(),
                profile.avatarUrl(),
                clock.instant());
    }

    static UUID requireAccount(@Nullable Actor actor) {
        if (actor == null || actor.accountId() == null) {
            throw new AccessDeniedException();
        }
        return actor.accountId();
    }
}
