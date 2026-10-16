package gag.nails.api.internal.http;

import gag.nails.api.internal.security.CurrentActorResolver;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Аргументы контроллеров слоя: {@code Actor}/{@code Optional<Actor>} вошедшего и {@code ClientInfo} запроса. */
@Configuration(proxyBeanMethods = false)
class WebConfiguration implements WebMvcConfigurer {

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentActorResolver());
        resolvers.add(new ClientInfoResolver());
    }
}
