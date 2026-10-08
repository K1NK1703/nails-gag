package gag.nails.schedule.model;

/** Что будет с днём при применении плана. */
public enum DayPlanStatus {
    /** Слоты будут созданы. */
    PLANNED,
    /** По шаблону недели — выходной. */
    DAY_OFF,
    /** В этот день уже есть хотя бы один слот: день пропускается. */
    ALREADY_OPEN
}
