package gag.nails.api.internal.auth;

import gag.nails.api.internal.auth.LoginRedirects.Failure;
import gag.nails.api.internal.http.ApiRequestAttributes;
import gag.nails.api.internal.me.Me;
import gag.nails.api.internal.me.MeAssembler;
import gag.nails.api.internal.security.SessionCookie;
import gag.nails.common.actor.Actor;
import gag.nails.common.error.UnauthenticatedException;
import gag.nails.identity.LoginFlow;
import gag.nails.identity.Sessions;
import gag.nails.identity.command.CompleteRegistration;
import gag.nails.identity.error.AccountBlockedException;
import gag.nails.identity.error.LoginFailedException;
import gag.nails.identity.error.ProviderUnavailableException;
import gag.nails.identity.error.RegistrationExpiredException;
import gag.nails.identity.model.Authenticated;
import gag.nails.identity.model.AuthorizationResponse;
import gag.nails.identity.model.ClientInfo;
import gag.nails.identity.model.LinkConfirmationRequired;
import gag.nails.identity.model.Linked;
import gag.nails.identity.model.LoginPurpose;
import gag.nails.identity.model.Provider;
import gag.nails.identity.model.RegistrationPrefill;
import gag.nails.identity.model.RegistrationRequired;
import gag.nails.identity.model.SignedIn;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Вход по HTTP (спецификация API, §6): начало входа у провайдера, обратный вызов, регистрация и подтверждение
 * привязки по билету, выход. Провайдер в пути — {@code yandex}, {@code vk} или {@code fake}; другое имя с адресом не
 * совпадает и даёт {@code 404}. Ответы не кэшируются ({@code Cache-Control: no-store} ставит фильтр заголовков).
 *
 * <p>Транзакций здесь нет: {@link LoginFlow} сам пишет короткими транзакциями, а внешняя потеряла бы журнал
 * неудачных и заблокированных входов. Токены, билеты, коды и {@code state} не логируются.
 */
@RestController
@RequestMapping("/api/auth")
class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private static final String PROVIDER = "/{provider:yandex|vk|fake}";

    private final LoginFlow loginFlow;
    private final Sessions sessions;
    private final SessionCookie sessionCookie;
    private final LoginStateCookie loginStateCookie;
    private final TicketCookie ticketCookie;
    private final LoginRedirects redirects;
    private final MeAssembler me;

    AuthController(
            LoginFlow loginFlow,
            Sessions sessions,
            SessionCookie sessionCookie,
            LoginStateCookie loginStateCookie,
            TicketCookie ticketCookie,
            LoginRedirects redirects,
            MeAssembler me) {
        this.loginFlow = loginFlow;
        this.sessions = sessions;
        this.sessionCookie = sessionCookie;
        this.loginStateCookie = loginStateCookie;
        this.ticketCookie = ticketCookie;
        this.redirects = redirects;
        this.me = me;
    }

    /** Начало входа: адрес провайдера и зашифрованное состояние в cookie. Привязка — только с сессией. */
    @PostMapping(PROVIDER + "/start")
    LoginRedirect start(
            @PathVariable Provider provider,
            @Valid @RequestBody StartLogin body,
            Optional<Actor> actor,
            HttpServletResponse response) {
        if (body.purpose() == LoginPurpose.LINK && actor.isEmpty()) {
            throw new UnauthenticatedException();
        }
        var start = loginFlow.start(provider, body.purpose(), actor.orElse(null));
        loginStateCookie.write(response, provider, start.state());
        return new LoginRedirect(start.authorizationUri());
    }

    /**
     * Возврат браузера от провайдера — всегда {@code 303} на страницу сайта (§6.3), cookie этапа входа снимается при
     * любом исходе. До модуля отсекается: нет или не расшифровалось состояние; нет {@code code} или {@code state}
     * (пользователь отказал, провайдер прислал {@code error}); привязка без сессии того же аккаунта, что начал её.
     * Совпадение {@code state} и срок проверяет модуль — так неудача попадает в журнал входов. Непредусмотренная
     * ошибка — тоже отказ {@code failed} (и ERROR в логе): ответ получает браузер, {@code problem+json} ему ни к чему.
     */
    @GetMapping(PROVIDER + "/callback")
    ResponseEntity<Void> callback(
            @PathVariable Provider provider,
            @RequestParam(required = false) @Nullable String code,
            @RequestParam(required = false) @Nullable String state,
            @RequestParam(name = "device_id", required = false) @Nullable String deviceId,
            @RequestParam(required = false) @Nullable String error,
            Optional<Actor> actor,
            ClientInfo client,
            HttpServletRequest request,
            HttpServletResponse response) {
        loginStateCookie.clear(response);
        var loginState = loginStateCookie.read(request, provider);
        if (loginState.isEmpty()) {
            return rejected(provider, "no readable login state");
        }
        if (error != null || !StringUtils.hasText(code) || !StringUtils.hasText(state)) {
            return rejected(provider, "no code or state from the provider");
        }
        var expected = loginState.get();
        if (expected.purpose() == LoginPurpose.LINK && !sameAccount(actor, expected.accountId())) {
            return rejected(provider, "link without the session that started it");
        }
        try {
            var result =
                    loginFlow.complete(provider, new AuthorizationResponse(code, state, deviceId), expected, client);
            return switch (result) {
                case SignedIn signedIn -> {
                    replaceSession(request, response, signedIn);
                    clearLeftoverTicket(request, response);
                    yield redirects.signedIn();
                }
                case RegistrationRequired required -> {
                    ticketCookie.write(response, required.ticket());
                    yield redirects.registration();
                }
                case LinkConfirmationRequired required -> {
                    ticketCookie.write(response, required.ticket());
                    yield redirects.linkConfirm();
                }
                case Linked ignored -> {
                    clearLeftoverTicket(request, response);
                    yield redirects.linked();
                }
            };
        } catch (LoginFailedException e) {
            return redirects.failure(Failure.FAILED);
        } catch (AccountBlockedException e) {
            return redirects.failure(Failure.BLOCKED);
        } catch (ProviderUnavailableException e) {
            return redirects.failure(Failure.PROVIDER);
        } catch (PessimisticLockingFailureException e) {
            // Код провайдера одноразовый и уже обменян: повторять нечего, человек начнёт вход заново.
            log.warn(
                    "{} login callback hit a database lock failure: {}",
                    provider,
                    e.getClass().getSimpleName());
            return redirects.failure(Failure.FAILED);
        } catch (RuntimeException e) {
            // Ответ получает браузер, а не JS: вместо problem+json — та же страница отказа. Токенов и кодов в
            // исключениях модуля нет, стек нужен для разбора.
            log.error("{} login callback failed", provider, e);
            return redirects.failure(Failure.FAILED);
        }
    }

    /** Подстановка для экрана регистрации по билету из cookie. */
    @GetMapping("/registration")
    RegistrationPrefill registrationPrefill(HttpServletRequest request) {
        var ticket = ticketCookie.read(request).orElseThrow(RegistrationExpiredException::new);
        return loginFlow.registrationPrefill(ticket).orElseThrow(RegistrationExpiredException::new);
    }

    /**
     * Завершение регистрации по билету: открывается сессия, билет снимается, ответ — профиль новой сессии. Ошибка формы
     * и устаревшая версия политики билет не трогают — человек исправит форму и отправит снова.
     */
    @PostMapping("/registration")
    Me register(
            @Valid @RequestBody CompleteRegistration command,
            ClientInfo client,
            HttpServletRequest request,
            HttpServletResponse response) {
        var ticket = ticketCookie.read(request).orElseThrow(RegistrationExpiredException::new);
        SignedIn signedIn;
        try {
            signedIn = loginFlow.completeRegistration(ticket, command, client);
        } catch (RegistrationExpiredException | AccountBlockedException | LoginFailedException e) {
            ticketCookie.clear(response);
            throw e;
        }
        replaceSession(request, response, signedIn);
        ticketCookie.clear(response);
        return meOf(signedIn.sessionToken());
    }

    /**
     * Подтверждение объединения аккаунтов при привязке чужого способа входа; билет снимается, ответ — профиль после
     * объединения.
     */
    @PostMapping("/link/confirm")
    Me confirmLink(Actor actor, ClientInfo client, HttpServletRequest request, HttpServletResponse response) {
        var ticket = ticketCookie.read(request).orElseThrow(RegistrationExpiredException::new);
        try {
            loginFlow.confirmLink(ticket, actor, client);
        } catch (RegistrationExpiredException | AccountBlockedException | LoginFailedException e) {
            ticketCookie.clear(response);
            throw e;
        }
        ticketCookie.clear(response);
        return meOf(ApiRequestAttributes.sessionToken(request).orElseThrow(UnauthenticatedException::new));
    }

    /** Выход: сессия закрывается, если она была, cookie снимается всегда — повтор безопасен. */
    @PostMapping("/logout")
    ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        ApiRequestAttributes.sessionToken(request).ifPresent(sessions::close);
        sessionCookie.clear(response);
        clearLeftoverTicket(request, response);
        return ResponseEntity.noContent().build();
    }

    /** «Выйти на всех устройствах»: все сессии аккаунта, включая текущую. */
    @PostMapping("/logout-all")
    ResponseEntity<Void> logoutAll(Actor actor, HttpServletResponse response) {
        sessions.closeAll(actor);
        sessionCookie.clear(response);
        return ResponseEntity.noContent().build();
    }

    /**
     * Привязку продолжает только сессия того аккаунта, что её начал. Состояние привязки без аккаунта модуль не
     * выдаёт; если оно всё же пришло — отказ, а не совпадение {@code null} с {@code null}.
     */
    private static boolean sameAccount(Optional<Actor> actor, @Nullable UUID stateAccountId) {
        return stateAccountId != null
                && stateAccountId.equals(actor.map(Actor::accountId).orElse(null));
    }

    /**
     * Новый вход в этом браузере: сначала cookie новой сессии, затем прежняя сессия, если запрос её нёс, закрывается —
     * открытой без владельца она остаться не должна. Закрытие — по возможности: вход уже состоялся, и сбой здесь не
     * должен его отменять (прежняя сессия истечёт сама). Токен в лог не пишется.
     */
    private void replaceSession(HttpServletRequest request, HttpServletResponse response, SignedIn signedIn) {
        sessionCookie.write(response, signedIn.sessionToken());
        ApiRequestAttributes.sessionToken(request).ifPresent(previous -> {
            try {
                sessions.close(previous);
            } catch (RuntimeException e) {
                log.warn("previous session close failed: {}", e.getClass().getSimpleName());
            }
        });
    }

    /**
     * Профиль сессии по её токену, перечитанный заново: после регистрации и объединения аккаунтов актор запроса уже
     * не тот (карточки не было, или к аккаунту пришёл чужой способ входа). Сессии нет — например, карточку только что
     * заблокировали — значит, и войти не вышло.
     */
    private Me meOf(String sessionToken) {
        var actor = sessions.authenticate(sessionToken)
                .map(Authenticated::actor)
                .orElseThrow(UnauthenticatedException::new);
        return me.of(actor);
    }

    /**
     * Брошенный билет регистрации (человек ушёл с экрана регистрации) снимается при входе, привязке и выходе: на общем
     * компьютере следующий человек не увидит по нему чужую подстановку.
     */
    private void clearLeftoverTicket(HttpServletRequest request, HttpServletResponse response) {
        if (ticketCookie.sent(request)) {
            ticketCookie.clear(response);
        }
    }

    private ResponseEntity<Void> rejected(Provider provider, String reason) {
        log.info("{} login callback rejected: {}", provider, reason);
        return redirects.failure(Failure.FAILED);
    }
}
