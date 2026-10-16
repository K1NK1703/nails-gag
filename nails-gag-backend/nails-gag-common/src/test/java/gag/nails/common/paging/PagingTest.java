package gag.nails.common.paging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import gag.nails.common.error.ValidationException;
import java.util.List;
import org.junit.jupiter.api.Test;

class PagingTest {

    @Test
    void rejectsNegativePage() {
        assertThatThrownBy(() -> new Paging(-1, 50))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).params()).containsEntry("field", "page"));
    }

    @Test
    void rejectsSizeOutsideOneToTwoHundred() {
        assertThatThrownBy(() -> new Paging(0, 0))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).params()).containsEntry("field", "size"));
        assertThatThrownBy(() -> new Paging(0, Paging.MAX_SIZE + 1))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).params()).containsEntry("field", "size"));

        assertThat(new Paging(0, 1).size()).isEqualTo(1);
        assertThat(new Paging(3, Paging.MAX_SIZE).offset()).isEqualTo(600);
    }

    @Test
    void rejectsOffsetBeyondIntRange() {
        assertThatThrownBy(() -> new Paging(Integer.MAX_VALUE / 2, 3))
                .isInstanceOf(ValidationException.class)
                .satisfies(e -> assertThat(((ValidationException) e).params()).containsEntry("field", "page"));

        assertThat(new Paging(Integer.MAX_VALUE / Paging.MAX_SIZE, Paging.MAX_SIZE).offset())
                .isLessThanOrEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void mapKeepsPageMetadata() {
        var paged = Paged.of(List.of(1, 2), new Paging(2, 2), 5);

        var mapped = paged.map(number -> "#" + number);

        assertThat(mapped).isEqualTo(new Paged<>(List.of("#1", "#2"), 2, 2, 5));
    }
}
