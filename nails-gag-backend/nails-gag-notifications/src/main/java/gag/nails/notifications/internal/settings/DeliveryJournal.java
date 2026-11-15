package gag.nails.notifications.internal.settings;

import gag.nails.common.paging.Paged;
import gag.nails.common.paging.Paging;
import gag.nails.notifications.command.DeliveryFilter;
import gag.nails.notifications.model.Channel;
import gag.nails.notifications.model.DeliveryStatus;
import gag.nails.notifications.model.DeliveryView;
import gag.nails.notifications.model.MessageKind;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Журнал доставок — чтение очереди как есть: доставка с полями своего сообщения. Условия отбора собираются из
 * заданных полей фильтра, значения — только параметрами запроса.
 */
@Component
class DeliveryJournal {

    private static final String FROM = """
             from notifications.delivery d
             join notifications.message m on m.id = d.message_id
            """;

    private final JdbcClient jdbc;

    DeliveryJournal(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Paged<DeliveryView> page(DeliveryFilter filter, Paging paging) {
        var conditions = new ArrayList<String>();
        var params = new LinkedHashMap<String, Object>();
        if (filter.status() != null) {
            conditions.add("d.status = :status");
            params.put("status", filter.status().name());
        }
        if (filter.bookingId() != null) {
            conditions.add("m.booking_id = :bookingId");
            params.put("bookingId", filter.bookingId());
        }
        var where = conditions.isEmpty() ? "" : " where " + String.join(" and ", conditions);
        var total = jdbc.sql("select count(*)" + FROM + where)
                .params(params)
                .query(Long.class)
                .single();
        var items = jdbc.sql("""
                        select d.id, d.message_id, m.kind, m.booking_id, m.client_id, m.account_id, d.channel,
                               d.status, d.attempts, d.error_code, m.due_at, d.sent_at
                        """ + FROM + where + """
                         order by m.due_at desc, d.id desc
                         limit :limit offset :offset
                        """)
                .params(params)
                .param("limit", paging.size())
                .param("offset", paging.offset())
                .query(DeliveryJournal::view)
                .list();
        return Paged.of(items, paging, total);
    }

    private static DeliveryView view(ResultSet row, int rowNumber) throws SQLException {
        return new DeliveryView(
                row.getObject("id", UUID.class),
                row.getObject("message_id", UUID.class),
                MessageKind.valueOf(row.getString("kind")),
                row.getObject("booking_id", UUID.class),
                row.getObject("client_id", UUID.class),
                row.getObject("account_id", UUID.class),
                Channel.valueOf(row.getString("channel")),
                DeliveryStatus.valueOf(row.getString("status")),
                row.getInt("attempts"),
                row.getString("error_code"),
                instant(row.getTimestamp("due_at")),
                nullableInstant(row.getTimestamp("sent_at")));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp.toInstant();
    }

    private static @Nullable Instant nullableInstant(@Nullable Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
