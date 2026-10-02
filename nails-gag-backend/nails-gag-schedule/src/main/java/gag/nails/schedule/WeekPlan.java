package gag.nails.schedule;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Неделя в предпросмотре: какой шаблон недели выпал по ротации и что будет с каждым днём периода. */
public record WeekPlan(LocalDate monday, UUID weekTemplateId, String weekTemplateName, List<DayPlan> days) {}
