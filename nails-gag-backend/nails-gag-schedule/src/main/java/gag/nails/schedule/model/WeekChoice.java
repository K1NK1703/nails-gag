package gag.nails.schedule.model;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.UUID;

/** Выбор шаблона недели для недели с данным понедельником — строка плана применения. */
public record WeekChoice(@NotNull LocalDate monday, @NotNull UUID weekTemplateId) {}
