package gag.nails.notifications.internal.preference;

/** Какие каналы включены у получателя: своя настройка аккаунта или умолчание. */
public record ChannelSwitches(boolean email, boolean telegram) {}
