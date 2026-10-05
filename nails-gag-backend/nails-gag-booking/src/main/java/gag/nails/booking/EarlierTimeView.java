package gag.nails.booking;

import java.time.Instant;

/** Предложение мастера приехать раньше начала слота и его судьба. */
public record EarlierTimeView(Instant proposedStart, Instant proposedAt, EarlierTimeStatus status) {}
