package gag.nails.analytics.internal.visitor;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.Optional;

/**
 * Кто зашёл — только для суточного отпечатка; не хранится. {@link #toString()} не показывает значений, чтобы адрес и
 * {@code User-Agent} не попали в лог случайно.
 *
 * @param address байты IP-адреса: 4 у IPv4, 16 у IPv6. Разные записи одного адреса дают одни байты — это делает
 *     JDK: {@link InetAddress#ofLiteral} разбирает литерал в число, а IPv4-mapped IPv6 ({@code ::ffff:a.b.c.d}) отдаёт
 *     как {@code Inet4Address}
 * @param userAgent заголовок {@code User-Agent}
 */
public record Visitor(byte[] address, String userAgent) {

    /**
     * Посетитель по IP-литералу, без обращения к DNS ({@link InetAddress#ofLiteral}).
     *
     * @return пусто, если {@code ip} — не IP-литерал
     */
    public static Optional<Visitor> of(String ip, String userAgent) {
        InetAddress address;
        try {
            address = InetAddress.ofLiteral(ip);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        return Optional.of(new Visitor(address.getAddress(), userAgent));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Visitor visitor
                && Arrays.equals(address, visitor.address)
                && userAgent.equals(visitor.userAgent);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(address) + userAgent.hashCode();
    }

    @Override
    public String toString() {
        return "Visitor[hidden]";
    }
}
