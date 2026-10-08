package gag.nails.identity.model;

/** Исход попытки входа в журнале {@code login_event}. */
public enum LoginOutcome {
    SIGNED_IN,
    REGISTERED,
    LINKED,
    BLOCKED,
    FAILED
}
