package gag.nails.finance.model;

import java.util.UUID;

/**
 * Категория доходов или расходов.
 *
 * @param hidden скрыта: не предлагается для новых записей, старые записи её показывают
 * @param position место в списке своего вида, с нуля
 */
public record CategoryView(UUID id, EntryKind kind, String name, boolean hidden, int position) {}
