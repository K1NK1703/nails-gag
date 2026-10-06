package gag.nails.identity;

/** Вид записи журнала прав. {@link #BOOTSTRAP} — выдача роли «Разработчик» по настройке сервера, без актора. */
public enum AccessChangeKind {
    ROLE_CREATED,
    ROLE_UPDATED,
    ROLE_DELETED,
    ROLE_ASSIGNED,
    ROLE_REVOKED,
    OVERRIDE_SET,
    OVERRIDE_CLEARED,
    BOOTSTRAP
}
