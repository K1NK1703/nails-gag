package gag.nails.api.internal.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RateLimitsTest {

    @Test
    void ipv4AddressIsItsOwnKey() {
        assertThat(RateLimits.ipKey("198.51.100.7")).isEqualTo("198.51.100.7");
        assertThat(RateLimits.ipKey("198.51.100.8")).isNotEqualTo(RateLimits.ipKey("198.51.100.7"));
        // IPv4, записанный как IPv6, — тот же клиент.
        assertThat(RateLimits.ipKey("::ffff:198.51.100.7")).isEqualTo("198.51.100.7");
    }

    @Test
    void ipv6AddressesOfOneSlash64ShareAKey() {
        assertThat(RateLimits.ipKey("2001:db8:1:2::1"))
                .isEqualTo(RateLimits.ipKey("2001:db8:1:2:ffff:ffff:ffff:ffff"))
                .isEqualTo(RateLimits.ipKey("2001:0db8:0001:0002:0:0:0:abcd"))
                .isEqualTo("2001:db8:1:2::/64");
    }

    @Test
    void ipv6AddressesOfDifferentSlash64HaveDifferentKeys() {
        assertThat(RateLimits.ipKey("2001:db8:1:2::1")).isNotEqualTo(RateLimits.ipKey("2001:db8:1:3::1"));
        assertThat(RateLimits.ipKey("2001:db8:1:2::1")).isNotEqualTo(RateLimits.ipKey("2001:db9:1:2::1"));
    }

    @Test
    void ipv6ZoneIdIsIgnored() {
        // Зона (%eth0, %2) не меняет адрес клиента: иначе каждая зона давала бы свою корзину.
        assertThat(RateLimits.ipKey("2001:db8:1:2::1%eth0"))
                .isEqualTo(RateLimits.ipKey("2001:db8:1:2::1%2"))
                .isEqualTo("2001:db8:1:2::/64");
        assertThat(RateLimits.ipKey("fe80::1%eth0")).isEqualTo(RateLimits.ipKey("fe80::2%eth1"));
    }
}
