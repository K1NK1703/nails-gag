package gag.nails.finance.internal.category;

import gag.nails.finance.model.EntryKind;
import java.util.UUID;

/** Категория глазами записей: вид, текущее название и скрыта ли. */
public record CategoryRef(UUID id, EntryKind kind, String name, boolean hidden) {}
