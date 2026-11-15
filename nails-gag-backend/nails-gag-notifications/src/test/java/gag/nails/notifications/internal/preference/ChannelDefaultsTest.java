package gag.nails.notifications.internal.preference;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

/** Умолчания сотрудника по ролям: роль «Мастер» среди прочих включает Telegram. */
class ChannelDefaultsTest {

    @Test
    void masterRoleWinsAmongOtherRoles() {
        assertThat(ChannelDefaults.forStaff(Set.of("MASTER", "DEVELOPER"))).isEqualTo(new ChannelSwitches(false, true));
        assertThat(ChannelDefaults.forStaff(Set.of("MASTER"))).isEqualTo(ChannelDefaults.MASTER);
    }

    @Test
    void staffWithoutMasterRoleGetsNothing() {
        assertThat(ChannelDefaults.forStaff(Set.of("DEVELOPER", "ACCOUNTANT")))
                .isEqualTo(new ChannelSwitches(false, false));
        assertThat(ChannelDefaults.forStaff(Set.of())).isEqualTo(ChannelDefaults.OTHER_STAFF);
    }
}
