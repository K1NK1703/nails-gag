package gag.nails.api.internal.error;

import static org.assertj.core.api.Assertions.assertThat;

import gag.nails.booking.error.LimitReachedException;
import gag.nails.common.error.UserFacingException;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.util.ClassUtils;

/** Новая ошибка модуля не попадает в API без решения о статусе: каждый наследник {@code UserFacingException} в таблице. */
class ErrorStatusCoverageTest {

    @Test
    void everyUserFacingExceptionHasStatus() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(UserFacingException.class));

        var types = scanner.findCandidateComponents("gag.nails").stream()
                .map(BeanDefinition::getBeanClassName)
                .map(Objects::requireNonNull)
                .<Class<?>>map(
                        name -> ClassUtils.resolveClassName(name, getClass().getClassLoader()))
                .toList();

        assertThat(types).contains(LimitReachedException.class).hasSizeGreaterThanOrEqualTo(41);
        assertThat(types)
                .as("исключения без статуса в ProblemStatuses")
                .allSatisfy(type -> assertThat(ProblemStatuses.covers(type))
                        .as(type.getName())
                        .isTrue());
    }
}
