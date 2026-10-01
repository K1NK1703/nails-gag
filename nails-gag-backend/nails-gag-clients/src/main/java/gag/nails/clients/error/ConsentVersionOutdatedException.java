package gag.nails.clients.error;

import gag.nails.clients.ConsentKind;
import gag.nails.common.error.UserFacingException;
import java.util.Map;

/** Под галочкой показали не действующую версию документа: сайт должен обновить текст и спросить снова. */
public final class ConsentVersionOutdatedException extends UserFacingException {

    public ConsentVersionOutdatedException(ConsentKind kind, String currentVersion) {
        super("clients.consent_version_outdated", Map.of("kind", kind, "currentVersion", currentVersion));
    }
}
