package gag.nails.common.actor;

/** Уровень доступа к разделу. Порядок значим: каждый следующий включает предыдущий. */
public enum AccessLevel {
    NONE,
    READ,
    WRITE;

    public boolean allows(AccessLevel required) {
        return compareTo(required) >= 0;
    }
}
