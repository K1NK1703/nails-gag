package gag.nails.finance.internal.entry;

import gag.nails.finance.model.EntryView;

/** Запись вместе с текущим названием её категории из того же запроса. */
record EntryRow(EntryEntity entry, String categoryName) {

    EntryView toView() {
        return entry.toView(categoryName);
    }
}
