package gag.nails.analytics.internal.visitor;

/**
 * Кто зашёл — только для суточного отпечатка; не хранится. {@link #toString()} не показывает значений, чтобы IP и
 * {@code User-Agent} не попали в лог случайно.
 */
public record Visitor(String ip, String userAgent) {

    @Override
    public String toString() {
        return "Visitor[hidden]";
    }
}
