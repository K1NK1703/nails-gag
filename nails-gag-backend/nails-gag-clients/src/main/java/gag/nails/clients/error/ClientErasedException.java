package gag.nails.clients.error;

import gag.nails.common.error.UserFacingException;
import java.util.Map;
import java.util.UUID;

/** Карточка обезличена после удаления аккаунта: менять её, объединять и записывать на неё нельзя. */
public final class ClientErasedException extends UserFacingException {

    public ClientErasedException(UUID clientId) {
        super("clients.erased", Map.of("clientId", clientId));
    }
}
