package gag.nails.api.internal.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.common.KeyValue;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.ServerHttpObservationFilter;

/** Разметка запроса аккаунтом (спецификация API, §13): MDC на время запроса и атрибут span-а запроса. */
class RequestTelemetryTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void tagsObservationContextAndMdc() {
        var request = new MockHttpServletRequest("GET", "/api/me");
        var context = new ServerRequestObservationContext(request, new MockHttpServletResponse());
        request.setAttribute(ServerHttpObservationFilter.CURRENT_OBSERVATION_CONTEXT_ATTRIBUTE, context);
        var accountId = UUID.randomUUID();

        var tagged = RequestTelemetry.tagAccount(request, accountId);
        assertThat(MDC.get(RequestTelemetry.MDC_ACCOUNT_ID)).isEqualTo(accountId.toString());
        tagged.close();

        assertThat(context.getHighCardinalityKeyValue(RequestTelemetry.SPAN_ACCOUNT_ID))
                .isEqualTo(KeyValue.of(RequestTelemetry.SPAN_ACCOUNT_ID, accountId.toString()));
        // В метрики аккаунт не попадает: у них только low-cardinality ключи.
        assertThat(context.getLowCardinalityKeyValue(RequestTelemetry.SPAN_ACCOUNT_ID))
                .isNull();
        assertThat(MDC.get(RequestTelemetry.MDC_ACCOUNT_ID)).isNull();
    }

    @Test
    void worksWithoutObservation() {
        var request = new MockHttpServletRequest("GET", "/api/me");
        var accountId = UUID.randomUUID();

        var tagged = RequestTelemetry.tagAccount(request, accountId);
        assertThat(MDC.get(RequestTelemetry.MDC_ACCOUNT_ID)).isEqualTo(accountId.toString());
        tagged.close();

        assertThat(MDC.get(RequestTelemetry.MDC_ACCOUNT_ID)).isNull();
    }
}
