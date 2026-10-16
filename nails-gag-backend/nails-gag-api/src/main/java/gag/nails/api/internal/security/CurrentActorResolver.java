package gag.nails.api.internal.security;

import gag.nails.api.internal.http.ApiRequestAttributes;
import gag.nails.common.actor.Actor;
import gag.nails.common.error.UnauthenticatedException;
import gag.nails.identity.model.Authenticated;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.core.MethodParameter;
import org.springframework.core.ResolvableType;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Аргумент контроллера — актор вошедшего, которого положил {@link SessionFilter}. Сигнатура решает, нужна ли сессия:
 * {@code Actor} — нужна, без неё {@link UnauthenticatedException} ({@code 401}); {@code Optional<Actor>} — адрес
 * работает и без входа. Других форм ({@code @Nullable Actor}) нет: правило должно читаться по типу. Публичный:
 * регистрирует его настройка Spring MVC из {@code internal.http}.
 */
public final class CurrentActorResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        var type = parameter.getParameterType();
        if (type == Actor.class) {
            return true;
        }
        return type == Optional.class
                && ResolvableType.forMethodParameter(parameter).getGeneric(0).resolve() == Actor.class;
    }

    @Override
    public Object resolveArgument(
            MethodParameter parameter,
            @Nullable ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            @Nullable WebDataBinderFactory binderFactory) {
        var request = webRequest.getNativeRequest(HttpServletRequest.class);
        var actor = Optional.ofNullable(request)
                .flatMap(ApiRequestAttributes::authenticated)
                .map(Authenticated::actor);
        if (parameter.getParameterType() == Optional.class) {
            return actor;
        }
        return actor.orElseThrow(UnauthenticatedException::new);
    }
}
