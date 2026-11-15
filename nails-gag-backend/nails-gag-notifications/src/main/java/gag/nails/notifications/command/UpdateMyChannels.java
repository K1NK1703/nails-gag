package gag.nails.notifications.command;

/** Включить или выключить каналы своего аккаунта; заменяет умолчания по роли. */
public record UpdateMyChannels(boolean emailEnabled, boolean telegramEnabled) {}
