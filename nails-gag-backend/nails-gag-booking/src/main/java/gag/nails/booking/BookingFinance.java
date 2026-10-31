package gag.nails.booking;

import gag.nails.booking.model.KeptPrepaymentFact;
import gag.nails.booking.model.VisitFact;
import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.error.ValidationException;
import java.time.LocalDate;
import java.util.List;

/**
 * Денежные факты записей для модуля финансов: доход от визитов и удержанных предоплат живёт только здесь, у финансов
 * копии нет. Нужен уровень чтения раздела «Финансы». Период — дни в поясе салона, обе границы включительно, не длиннее
 * 366 дней. Каждый метод — один запрос к базе, сколько бы записей ни попало в период. Клиента факты не содержат.
 */
public interface BookingFinance {

    /** Наибольшая длина периода в днях, считая обе границы. */
    int MAX_PERIOD_DAYS = 366;

    /**
     * Выполненные визиты, день визита которых (согласованное более раннее время, если есть, иначе начало слота; в
     * поясе салона) попадает в период; по началу визита.
     *
     * @throws ValidationException если {@code to} раньше {@code from} или период длиннее {@value #MAX_PERIOD_DAYS}
     *     дней ({@code to})
     * @throws AccessDeniedException если у актора нет чтения раздела «Финансы»
     */
    List<VisitFact> visits(LocalDate from, LocalDate to, Actor actor);

    /**
     * Удержанные предоплаты (исход по правилу записи с учётом переопределения мастером), день отмены или неявки
     * которых попадает в период; по моменту отмены или неявки.
     *
     * @throws ValidationException если {@code to} раньше {@code from} или период длиннее {@value #MAX_PERIOD_DAYS}
     *     дней ({@code to})
     * @throws AccessDeniedException если у актора нет чтения раздела «Финансы»
     */
    List<KeptPrepaymentFact> keptPrepayments(LocalDate from, LocalDate to, Actor actor);
}
