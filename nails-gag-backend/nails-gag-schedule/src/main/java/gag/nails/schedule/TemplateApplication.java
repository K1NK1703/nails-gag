package gag.nails.schedule;

import java.time.LocalDate;
import java.util.List;

/** Итог применения шаблона: созданные слоты и даты, пропущенные потому, что день уже был открыт. */
public record TemplateApplication(List<SlotView> created, List<LocalDate> skipped) {}
