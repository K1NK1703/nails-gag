package gag.nails.api.internal.http;

import gag.nails.identity.model.ClientInfo;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Аргумент контроллера типа {@link ClientInfo} — сведения о запросе для журнала входов. IP — {@code getRemoteAddr()}:
 * за nginx его уже подставил Tomcat из {@code X-Forwarded-For} доверенного прокси
 * ({@code server.forward-headers-strategy: native}), заголовку от посторонних адресов не верят. {@code User-Agent}
 * обрезается до {@value #USER_AGENT_MAX_LENGTH} символов — столько хранит журнал.
 */
class ClientInfoResolver implements HandlerMethodArgumentResolver {

    static final int USER_AGENT_MAX_LENGTH = 512;

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == ClientInfo.class;
    }

    @Override
    public ClientInfo resolveArgument(
            MethodParameter parameter,
            @Nullable ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            @Nullable WebDataBinderFactory binderFactory) {
        var request = webRequest.getNativeRequest(HttpServletRequest.class);
        if (request == null) {
            return new ClientInfo(null, null);
        }
        return new ClientInfo(request.getRemoteAddr(), truncate(request.getHeader(HttpHeaders.USER_AGENT)));
    }

    /** Не режет суррогатную пару пополам: обрезанная строка остаётся корректным UTF-16. */
    static @Nullable String truncate(@Nullable String userAgent) {
        if (userAgent == null || userAgent.length() <= USER_AGENT_MAX_LENGTH) {
            return userAgent;
        }
        var end = Character.isHighSurrogate(userAgent.charAt(USER_AGENT_MAX_LENGTH - 1))
                ? USER_AGENT_MAX_LENGTH - 1
                : USER_AGENT_MAX_LENGTH;
        return userAgent.substring(0, end);
    }
}
