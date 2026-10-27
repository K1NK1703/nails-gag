package gag.nails.files.error;

import gag.nails.common.error.UserFacingException;

/** Все места обработки картинок заняты дольше, чем загрузка готова ждать; повторить чуть позже. */
public final class ProcessingBusyException extends UserFacingException {

    public ProcessingBusyException() {
        super("files.busy");
    }
}
