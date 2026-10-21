package gag.nails.booking.error;

import gag.nails.common.error.UserFacingException;

/**
 * Клиенту фото-референсы этой записи уже не изменить: визит начался. Приложить новый нельзя и к отменённой записи.
 */
public final class ReferencesClosedException extends UserFacingException {

    public ReferencesClosedException() {
        super("booking.references_closed");
    }
}
