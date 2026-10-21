package gag.nails.portfolio;

import gag.nails.common.actor.Actor;
import gag.nails.common.error.AccessDeniedException;
import gag.nails.common.paging.Paged;
import gag.nails.common.paging.Paging;
import gag.nails.portfolio.error.WorkNotFoundException;
import gag.nails.portfolio.model.PublicWork;
import gag.nails.portfolio.model.WorkView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Чтение портфолио. Работа опубликована, если мастер её показывает ({@code visible}) и её источник —
 * {@code CLIENT_OUTSIDE_SITE} или {@code EXAMPLE}, либо {@code CLIENT} и у клиента есть согласие на публикацию фото.
 * Публичный список и публичная отдача фото проверяют одно и то же правило.
 */
public interface PortfolioQueries {

    /**
     * Опубликованные работы по порядку мастера, без входа. Страница и {@code total} считаются по одному правилу
     * публичности в одном снимке базы.
     */
    Paged<PublicWork> published(Paging paging);

    /**
     * Все работы по порядку мастера, со скрытыми и состоянием согласия. Нужен уровень чтения раздела «Портфолио».
     *
     * @throws AccessDeniedException если у актора нет чтения «Портфолио»
     */
    List<WorkView> all(Actor actor);

    /**
     * Нужен уровень чтения раздела «Портфолио».
     *
     * @throws AccessDeniedException если у актора нет чтения «Портфолио»
     * @throws WorkNotFoundException если работы нет
     */
    WorkView get(UUID workId, Actor actor);

    /**
     * Картинку файла можно отдать: работа с этим файлом опубликована, или актор читает «Портфолио» и это файл
     * портфолио (в том числе ещё не привязанный к работе — мастер смотрит загруженное фото до сохранения работы).
     *
     * @param actor вошедший; пусто — аноним
     */
    boolean imageVisible(UUID fileId, Optional<Actor> actor);

    /** Работа с этим файлом опубликована: картинку можно кэшировать публично. */
    boolean imagePublished(UUID fileId);
}
