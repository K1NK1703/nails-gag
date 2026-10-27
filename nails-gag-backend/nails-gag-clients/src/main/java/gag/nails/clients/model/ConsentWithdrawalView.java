package gag.nails.clients.model;

import java.time.Instant;

/**
 * Запись журнала отзывов: какой вид, когда и кем отозван. Один отзыв снимает все неотозванные (по любой редакции)
 * согласия этого вида у клиента и даёт одну запись, сколько бы строк согласий он ни затронул.
 */
public record ConsentWithdrawalView(ConsentKind kind, Instant withdrawnAt, WithdrawnBy withdrawnBy) {}
