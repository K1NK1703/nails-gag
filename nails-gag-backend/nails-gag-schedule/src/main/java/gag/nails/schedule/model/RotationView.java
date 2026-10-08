package gag.nails.schedule.model;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Ротация недель: шаблоны недель по порядку и якорь.
 *
 * @param weekTemplateIds порядок чередования; пусто — ротация не настроена
 * @param anchorMonday понедельник недели, на которую приходится шаблон с индексом {@code anchorIndex};
 *     остальные недели считаются от него вперёд и назад по кругу
 */
public record RotationView(List<UUID> weekTemplateIds, LocalDate anchorMonday, int anchorIndex) {}
