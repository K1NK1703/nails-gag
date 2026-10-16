package gag.nails.api.internal.me;

import gag.nails.clients.model.ClientView;
import gag.nails.identity.model.AccountView;
import org.jspecify.annotations.Nullable;

/**
 * Профиль вошедшего (спецификация API, §7.1): ответ {@code GET /api/me}, регистрации и подтверждения привязки.
 * Публичный: его отдают и адреса входа.
 *
 * @param account аккаунт; уровни по разделам — в {@code access}, по ним фронт показывает доступные разделы
 * @param client своя карточка клиента; пусто, если у аккаунта её нет
 */
public record Me(AccountView account, @Nullable ClientView client) {}
