package gag.nails.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.error.ValidationException;
import gag.nails.testsupport.ModuleTest;
import gag.nails.testsupport.MutableClock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@ModuleTest
class PrepaymentFlagTest {

    @Autowired
    ClientService clients;

    @Autowired
    ClientQueries queries;

    @Autowired
    MutableClock clock;

    private final Actor master = Actor.master();
    private ClientView maria;

    @BeforeEach
    void createClient() {
        maria = clients.create(new CreateClient("Мария", null, null, null, null, null), master);
    }

    @Test
    void systemSetsFlagWithReasonAndBooking() {
        var booking = UUID.randomUUID();

        clients.applySystemPrepaymentChange(
                maria.id(), new PrepaymentFlagChange(true, PrepaymentFlagReason.LATE_CANCELLATION, booking, null));

        assertThat(queries.get(maria.id(), master).prepaymentRequired()).isTrue();
        assertThat(queries.prepaymentHistory(maria.id(), master))
                .singleElement()
                .satisfies(change -> {
                    assertThat(change.required()).isTrue();
                    assertThat(change.reason()).isEqualTo(PrepaymentFlagReason.LATE_CANCELLATION);
                    assertThat(change.setBy()).isEqualTo(ChangedBy.SYSTEM);
                    assertThat(change.bookingId()).isEqualTo(booking);
                });
    }

    @Test
    void systemCannotUseMasterReasons() {
        assertThatThrownBy(() -> clients.applySystemPrepaymentChange(
                        maria.id(), new PrepaymentFlagChange(true, PrepaymentFlagReason.MASTER_SET, null, null)))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).params()).containsEntry("field", "reason"));
    }

    @Test
    void masterWaivesAndSetsFlagWithComment() {
        clients.applySystemPrepaymentChange(
                maria.id(), new PrepaymentFlagChange(true, PrepaymentFlagReason.NO_SHOW, null, null));

        clock.advance(Duration.ofMinutes(1));
        clients.setPrepaymentRequired(maria.id(), false, "постоянная клиентка", master);
        assertThat(queries.get(maria.id(), master).prepaymentRequired()).isFalse();

        clock.advance(Duration.ofMinutes(1));
        clients.setPrepaymentRequired(maria.id(), true, null, master);
        assertThat(queries.get(maria.id(), master).prepaymentRequired()).isTrue();

        assertThat(queries.prepaymentHistory(maria.id(), master))
                .extracting(PrepaymentFlagChangeView::reason)
                .containsExactly(
                        PrepaymentFlagReason.MASTER_SET,
                        PrepaymentFlagReason.MASTER_WAIVED,
                        PrepaymentFlagReason.NO_SHOW);
        assertThat(queries.prepaymentHistory(maria.id(), master).get(1).comment())
                .isEqualTo("постоянная клиентка");
    }

    @Test
    void historyIsOrderedNewestFirst() {
        clients.applySystemPrepaymentChange(
                maria.id(), new PrepaymentFlagChange(true, PrepaymentFlagReason.NO_SHOW, null, null));
        clock.advance(Duration.ofMinutes(1));
        clients.applySystemPrepaymentChange(
                maria.id(),
                new PrepaymentFlagChange(false, PrepaymentFlagReason.VISIT_WITH_PREPAYMENT_COMPLETED, null, null));

        assertThat(queries.prepaymentHistory(maria.id(), master))
                .extracting(PrepaymentFlagChangeView::required)
                .containsExactly(false, true);
    }

    @Test
    void clientSeesOwnFlagButNotHistoryAndCannotChangeIt() {
        var me = Actor.client(maria.id());

        assertThat(queries.get(maria.id(), me).prepaymentRequired()).isFalse();
        assertThatThrownBy(() -> queries.prepaymentHistory(maria.id(), me)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> clients.setPrepaymentRequired(maria.id(), true, null, me))
                .isInstanceOf(AccessDeniedException.class);
    }
}
