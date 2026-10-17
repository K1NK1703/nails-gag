package gag.nails.api.internal.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import gag.nails.common.error.InternalException;
import jakarta.servlet.FilterChain;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Строка журнала запроса без Spring: упавшая цепочка и длинный путь. */
class RequestLogFilterTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(RequestLogFilter.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attachAppender() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void failedChainIsLoggedAsServerError() {
        var request = new MockHttpServletRequest("GET", "/api/me");
        FilterChain failing = (req, res) -> {
            throw new InternalException("chain failed");
        };

        assertThatThrownBy(() -> new RequestLogFilter().doFilter(request, new MockHttpServletResponse(), failing))
                .isInstanceOf(InternalException.class);

        assertThat(messages()).singleElement().asString().matches("GET /api/me -> 500 \\d+ms");
    }

    @Test
    void longPathIsTruncated() throws Exception {
        var path = "/api/" + "a".repeat(5000);
        var request = new MockHttpServletRequest("GET", path);

        new RequestLogFilter().doFilter(request, new MockHttpServletResponse(), (req, res) -> {});

        assertThat(messages())
                .singleElement()
                .asString()
                .startsWith("GET " + path.substring(0, RequestLogFilter.MAX_PATH_LENGTH) + "… -> 200 ");
    }

    private List<String> messages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }
}
