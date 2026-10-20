package gag.nails.clients.model;

import java.time.Instant;

/**
 * Отзыв согласия в журнале: какого вида, когда и кем. Один отзыв снимает все действовавшие согласия этого вида
 * у клиента, поэтому в журнале он один, сколько бы строк согласий ни затронул.
 */
public record ConsentWithdrawalView(ConsentKind kind, Instant withdrawnAt, WithdrawnBy withdrawnBy) {}
