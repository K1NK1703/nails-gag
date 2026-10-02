package gag.nails.common.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class ConstraintViolationsTest {

    @Test
    void returnsNameOfViolatedConstraintFromHibernateCause() {
        var hibernate = new ConstraintViolationException(
                "conflicting key value", new SQLException("exclusion", "23P01"), "slot_no_overlap");
        var spring = new DataIntegrityViolationException("could not execute statement", hibernate);

        assertThat(ConstraintViolations.constraintName(spring)).contains("slot_no_overlap");
    }

    @Test
    void fallsBackToConstraintNameInDriverMessage() {
        var driver = new SQLException(
                "ERROR: conflicting key value violates exclusion constraint \"slot_no_overlap\"", "23P01");
        var spring = new DataIntegrityViolationException("could not execute statement", driver);

        assertThat(ConstraintViolations.constraintName(spring)).contains("slot_no_overlap");
    }

    @Test
    void isEmptyWhenCauseChainHasNoConstraintName() {
        var spring = new DataIntegrityViolationException("could not execute statement", new SQLException("boom"));

        assertThat(ConstraintViolations.constraintName(spring)).isEmpty();
    }
}
