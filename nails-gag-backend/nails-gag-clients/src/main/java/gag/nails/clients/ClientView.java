package gag.nails.clients;

import java.time.MonthDay;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Клиент без заметки мастера и причины блокировки (их отдают {@link ClientQueries#masterNote} и
 * {@link ClientQueries#blockReason}: клиент читает своё представление, а они ему не видны).
 *
 * @param phone доверенный номер в формате E.164 ({@code +79211234567}): подтверждённый провайдером
 *     входа или введённый сотрудником; у карточки от мастера может отсутствовать
 * @param birthday день и месяц рождения без года
 * @param accountId аккаунт входа; пусто у карточки без аккаунта
 * @param contactPhone номер для связи, который клиент указал сам: не подтверждён, не уникален
 * @param blocked клиент заблокирован сотрудником
 * @param erased карточка обезличена после удаления аккаунта: имя — «Удалённый клиент», личных данных нет
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
        boolean prepaymentRequired,
        @Nullable String contactPhone,
        boolean blocked,
        boolean erased) {}
