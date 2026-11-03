package gag.nails.finance.internal.recurring;

import gag.nails.finance.model.RecurringView;

/** Шаблон вместе с текущим названием его категории из того же запроса. */
record RecurringRow(RecurringEntity recurring, String categoryName) {

    RecurringView toView() {
        return recurring.toView(categoryName);
    }
}
