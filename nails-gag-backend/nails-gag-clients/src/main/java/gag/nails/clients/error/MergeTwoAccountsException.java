package gag.nails.clients.error;

import gag.nails.common.error.UserFacingException;

/** У обоих клиентов есть аккаунт входа: объединить их нельзя, иначе один из входов перестанет работать. */
public final class MergeTwoAccountsException extends UserFacingException {

    public MergeTwoAccountsException() {
        super("clients.merge_two_accounts");
    }
}
