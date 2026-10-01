package gag.nails.clients;

import java.time.MonthDay;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Клиент без заметки мастера (её отдаёт {@link ClientQueries#masterNote}).
 *
 * @param phone в формате E.164 ({@code +79211234567}); у карточки от мастера может отсутствовать
 * @param birthday день и месяц рождения без года
 * @param accountId аккаунт входа; пусто у карточки без аккаунта
 */
public record ClientView(
        UUID id,
        String firstName,
        @Nullable String lastName,
        @Nullable String patronymic,
        @Nullable String phone,
        @Nullable String email,
        @Nullable MonthDay birthday,
        ClientSource source,
        @Nullable UUID accountId,
        boolean prepaymentRequired) {}
