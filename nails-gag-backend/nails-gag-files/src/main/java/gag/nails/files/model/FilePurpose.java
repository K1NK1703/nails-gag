package gag.nails.files.model;

import java.util.List;

/**
 * Назначение файла: от него зависят ширины вариантов и каталог на диске. Владелец привязывает файл только своего
 * назначения.
 */
public enum FilePurpose {

    /** Фото работы в портфолио: три ширины для {@code srcset} на лендинге. */
    PORTFOLIO(List.of(480, 960, 1600)),

    /** Фото-референс к записи: одна ширина, чтобы мастер разглядел детали. */
    REFERENCE(List.of(2000));

    private final List<Integer> widths;

    FilePurpose(List<Integer> widths) {
        this.widths = widths;
    }

    /**
     * Ширины вариантов в пикселях по возрастанию. Вариант не шире оригинала: если оригинал уже, вариант этой ширины
     * хранит картинку в размере оригинала.
     */
    public List<Integer> widths() {
        return widths;
    }

    /** Наибольшая ширина варианта. */
    public int maxWidth() {
        return widths.getLast();
    }
}
