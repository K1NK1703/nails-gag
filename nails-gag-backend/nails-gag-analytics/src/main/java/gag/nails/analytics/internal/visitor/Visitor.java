package gag.nails.analytics.internal.visitor;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.Optional;

/**
 * Кто зашёл — только для суточного отпечатка; не хранится. {@link #toString()} не показывает значений, чтобы адрес и
 * {@code User-Agent} не попали в лог случайно.
 *
 * @param address байты IP-адреса в каноническом виде: 4 байта IPv4 (в том числе из IPv4-mapped IPv6), 16 байт IPv6 —
 *     разные записи одного адреса дают один отпечаток
 * @param userAgent заголовок {@code User-Agent}
 */
public record Visitor(byte[] address, String userAgent) {

    private static final int IPV4_BYTES = 4;
    private static final int IPV6_BYTES = 16;
    private static final int MAPPED_PREFIX_ZEROS = 10;

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
        return Optional.of(new Visitor(canonical(address.getAddress()), userAgent));
    }

    /** IPv4-mapped IPv6 ({@code ::ffff:a.b.c.d}) — те же 4 байта, что у IPv4. */
    private static byte[] canonical(byte[] address) {
        if (address.length == IPV6_BYTES && isMappedIpv4(address)) {
            return Arrays.copyOfRange(address, IPV6_BYTES - IPV4_BYTES, IPV6_BYTES);
        }
        return address;
    }

    private static boolean isMappedIpv4(byte[] address) {
        for (var i = 0; i < MAPPED_PREFIX_ZEROS; i++) {
            if (address[i] != 0) {
                return false;
            }
        }
        return address[MAPPED_PREFIX_ZEROS] == (byte) 0xff && address[MAPPED_PREFIX_ZEROS + 1] == (byte) 0xff;
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
