package gag.nails.clients;

/** Откуда появился клиент: зарегистрировался сам или внесён мастером как карточка без аккаунта. */
public enum ClientSource {
    SELF_REGISTERED,
    ADDED_BY_MASTER
}
