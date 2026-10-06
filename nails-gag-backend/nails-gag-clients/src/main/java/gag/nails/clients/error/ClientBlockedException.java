package gag.nails.clients.error;

import gag.nails.common.error.UserFacingException;
import java.util.Map;
import java.util.UUID;

/** Клиент заблокирован сотрудником: записываться и входить он не может, пока его не разблокируют. */
public final class ClientBlockedException extends UserFacingException {

    public ClientBlockedException(UUID clientId) {
        super("clients.blocked", Map.of("clientId", clientId));
    }
}
