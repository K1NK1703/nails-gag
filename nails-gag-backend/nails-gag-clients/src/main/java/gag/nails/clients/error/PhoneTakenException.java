package gag.nails.clients.error;

import gag.nails.common.error.UserFacingException;
import java.util.Map;

/** Телефон уже есть у другого клиента. */
public final class PhoneTakenException extends UserFacingException {

    public PhoneTakenException(String phone) {
        super("clients.phone_taken", Map.of("phone", phone));
    }
}
