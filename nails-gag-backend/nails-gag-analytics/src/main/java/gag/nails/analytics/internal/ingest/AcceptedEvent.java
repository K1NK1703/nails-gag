package gag.nails.analytics.internal.ingest;

import gag.nails.analytics.internal.count.Tally;
import gag.nails.analytics.internal.visitor.Visitor;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Проверенное событие: что прибавить и, у просмотра страницы, кого отметить посетителем дня.
 *
 * @param tallies счётчики в порядке прибавления: счётчик события, затем источник и устройство
 * @param visitor посетитель для отпечатка — только у {@code page_view}
 */
record AcceptedEvent(List<Tally> tallies, @Nullable Visitor visitor) {}
