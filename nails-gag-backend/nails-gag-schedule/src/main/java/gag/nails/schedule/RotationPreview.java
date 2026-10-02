package gag.nails.schedule;

import java.time.LocalDate;
import java.util.List;

/** Предпросмотр применения ротации к периоду; недели идут по порядку, дни — только внутри периода. */
public record RotationPreview(LocalDate from, LocalDate to, List<WeekPlan> weeks) {}
