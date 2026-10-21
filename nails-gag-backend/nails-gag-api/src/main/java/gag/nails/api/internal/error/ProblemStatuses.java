package gag.nails.api.internal.error;

import static java.util.Map.entry;

import gag.nails.booking.error.BookingNotFoundException;
import gag.nails.booking.error.ConsentMissingException;
import gag.nails.booking.error.CorrectionWindowClosedException;
import gag.nails.booking.error.InvalidTransitionException;
import gag.nails.booking.error.LateRescheduleNotAllowedException;
import gag.nails.booking.error.LimitReachedException;
import gag.nails.booking.error.ReferencesClosedException;
import gag.nails.booking.error.ReferencesLimitReachedException;
import gag.nails.booking.error.TooLateToBookException;
import gag.nails.booking.error.VisitNotStartedException;
import gag.nails.catalog.error.ServiceInactiveException;
import gag.nails.catalog.error.ServiceNotFoundException;
import gag.nails.clients.error.ClientBlockedException;
import gag.nails.clients.error.ClientErasedException;
import gag.nails.clients.error.ClientNotFoundException;
import gag.nails.clients.error.ConsentRequiredException;
import gag.nails.clients.error.ConsentVersionOutdatedException;
import gag.nails.clients.error.DocumentVersionDateTakenException;
import gag.nails.clients.error.DocumentVersionExistsException;
import gag.nails.clients.error.DocumentVersionNotFoundException;
import gag.nails.clients.error.PhoneTakenException;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.error.UnauthenticatedException;
import gag.nails.common.error.UserFacingException;
import gag.nails.common.error.ValidationException;
import gag.nails.files.error.ProcessingBusyException;
import gag.nails.files.error.TooLargeFileException;
import gag.nails.files.error.TooLargeImageException;
import gag.nails.files.error.UnsupportedFormatException;
import gag.nails.identity.error.AccountBlockedException;
import gag.nails.identity.error.AccountNotFoundException;
import gag.nails.identity.error.LastAccessAdminException;
import gag.nails.identity.error.LoginFailedException;
import gag.nails.identity.error.ProviderUnavailableException;
import gag.nails.identity.error.RegistrationExpiredException;
import gag.nails.identity.error.RoleBuiltInException;
import gag.nails.identity.error.RoleCodeTakenException;
import gag.nails.identity.error.RoleInUseException;
import gag.nails.identity.error.RoleNotFoundException;
import gag.nails.schedule.error.DayTemplateInUseException;
import gag.nails.schedule.error.DayTemplateNotFoundException;
import gag.nails.schedule.error.RotationNotConfiguredException;
import gag.nails.schedule.error.SlotNotFoundException;
import gag.nails.schedule.error.SlotOccupiedException;
import gag.nails.schedule.error.SlotOverlapException;
import gag.nails.schedule.error.TemplateNameTakenException;
import gag.nails.schedule.error.TemplateOverlapException;
import gag.nails.schedule.error.WeekTemplateInUseException;
import gag.nails.schedule.error.WeekTemplateNotFoundException;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/**
 * Статусы ответов: явная таблица «класс ошибки модуля → статус», без умолчания, и коды слоя для ошибок, у
 * которых своего кода нет. Поиск по точному классу; ошибка вне таблицы — недосмотр, она отвечает {@code 500}.
 * Полноту таблицы проверяет {@code ErrorStatusCoverageTest}.
 */
final class ProblemStatuses {

    static final String VALIDATION = "common.validation";
    static final String UNAUTHENTICATED = "common.unauthenticated";
    static final String ACCESS_DENIED = "common.access_denied";
    static final String NOT_FOUND = "common.not_found";
    static final String METHOD_NOT_ALLOWED = "common.method_not_allowed";
    static final String NOT_ACCEPTABLE = "common.not_acceptable";
    static final String UNSUPPORTED_MEDIA_TYPE = "common.unsupported_media_type";
    static final String BAD_REQUEST = "common.bad_request";
    static final String BUSY = "common.busy";
    static final String INTERNAL = "common.internal";

    /**
     * {@link AccessDeniedException} здесь — статус для сотрудника; клиенту и анониму обработчик отвечает иначе
     * (спецификация API, §4.6).
     */
    private static final Map<Class<? extends UserFacingException>, HttpStatus> STATUSES = Map.ofEntries(
            // common
            entry(ValidationException.class, HttpStatus.BAD_REQUEST),
            entry(UnauthenticatedException.class, HttpStatus.UNAUTHORIZED),
            entry(AccessDeniedException.class, HttpStatus.FORBIDDEN),
            // files
            entry(UnsupportedFormatException.class, HttpStatus.BAD_REQUEST),
            entry(TooLargeImageException.class, HttpStatus.BAD_REQUEST),
            entry(TooLargeFileException.class, HttpStatus.CONTENT_TOO_LARGE),
            entry(ProcessingBusyException.class, HttpStatus.SERVICE_UNAVAILABLE),
            // catalog
            entry(ServiceNotFoundException.class, HttpStatus.NOT_FOUND),
            entry(ServiceInactiveException.class, HttpStatus.CONFLICT),
            // clients
            entry(ClientNotFoundException.class, HttpStatus.NOT_FOUND),
            entry(DocumentVersionNotFoundException.class, HttpStatus.NOT_FOUND),
            entry(ClientBlockedException.class, HttpStatus.FORBIDDEN),
            entry(ClientErasedException.class, HttpStatus.FORBIDDEN),
            entry(PhoneTakenException.class, HttpStatus.CONFLICT),
            entry(ConsentVersionOutdatedException.class, HttpStatus.CONFLICT),
            entry(ConsentRequiredException.class, HttpStatus.CONFLICT),
            entry(DocumentVersionExistsException.class, HttpStatus.CONFLICT),
            entry(DocumentVersionDateTakenException.class, HttpStatus.CONFLICT),
            // schedule
            entry(SlotNotFoundException.class, HttpStatus.NOT_FOUND),
            entry(DayTemplateNotFoundException.class, HttpStatus.NOT_FOUND),
            entry(WeekTemplateNotFoundException.class, HttpStatus.NOT_FOUND),
            entry(SlotOccupiedException.class, HttpStatus.CONFLICT),
            entry(SlotOverlapException.class, HttpStatus.CONFLICT),
            entry(TemplateOverlapException.class, HttpStatus.CONFLICT),
            entry(TemplateNameTakenException.class, HttpStatus.CONFLICT),
            entry(DayTemplateInUseException.class, HttpStatus.CONFLICT),
            entry(WeekTemplateInUseException.class, HttpStatus.CONFLICT),
            entry(RotationNotConfiguredException.class, HttpStatus.CONFLICT),
            // booking
            entry(BookingNotFoundException.class, HttpStatus.NOT_FOUND),
            entry(InvalidTransitionException.class, HttpStatus.CONFLICT),
            entry(LimitReachedException.class, HttpStatus.CONFLICT),
            entry(TooLateToBookException.class, HttpStatus.CONFLICT),
            entry(LateRescheduleNotAllowedException.class, HttpStatus.CONFLICT),
            entry(CorrectionWindowClosedException.class, HttpStatus.CONFLICT),
            entry(VisitNotStartedException.class, HttpStatus.CONFLICT),
            entry(ConsentMissingException.class, HttpStatus.CONFLICT),
            entry(ReferencesLimitReachedException.class, HttpStatus.CONFLICT),
            entry(ReferencesClosedException.class, HttpStatus.CONFLICT),
            // identity
            entry(LoginFailedException.class, HttpStatus.UNAUTHORIZED),
            entry(RegistrationExpiredException.class, HttpStatus.UNAUTHORIZED),
            entry(AccountBlockedException.class, HttpStatus.FORBIDDEN),
            entry(AccountNotFoundException.class, HttpStatus.NOT_FOUND),
            entry(RoleNotFoundException.class, HttpStatus.NOT_FOUND),
            entry(LastAccessAdminException.class, HttpStatus.CONFLICT),
            entry(RoleInUseException.class, HttpStatus.CONFLICT),
            entry(RoleBuiltInException.class, HttpStatus.CONFLICT),
            entry(RoleCodeTakenException.class, HttpStatus.CONFLICT),
            entry(ProviderUnavailableException.class, HttpStatus.SERVICE_UNAVAILABLE));

    private ProblemStatuses() {}

    /** Статус ошибки модуля; пусто — класса нет в таблице. */
    static Optional<HttpStatus> of(UserFacingException exception) {
        return Optional.ofNullable(STATUSES.get(exception.getClass()));
    }

    static boolean covers(Class<?> type) {
        return STATUSES.containsKey(type);
    }

    /** Код слоя для ошибки, у которой есть только статус: исключения Spring MVC и {@code /error}. */
    static String codeOf(HttpStatusCode status) {
        return switch (status.value()) {
            case 401 -> UNAUTHENTICATED;
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 406 -> NOT_ACCEPTABLE;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            default -> status.is4xxClientError() ? BAD_REQUEST : INTERNAL;
        };
    }
}
