package gag.nails.booking.error;

import gag.nails.common.error.UserFacingException;

/**
 * Клиенту фото-референсы этой записи уже не изменить: визит начался (в согласованное более раннее время или в начале
 * слота). Приложить новый нельзя и к записи, которая уже не активна.
 */
public final class ReferencesClosedException extends UserFacingException {

    public ReferencesClosedException() {
        super("booking.references_closed");
    }
}
