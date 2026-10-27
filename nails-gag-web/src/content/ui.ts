import { plural } from '../lib/plural'

/** Общие строки интерфейса, не относящиеся к конкретному разделу контента. */
export const ui = {
  legal: {
    version: 'Версия',
    updated: 'обновлено',
    toc: 'Содержание',
  },
  /** Подпись мастера в карточке героя и в alt фото. */
  masterRole: 'мастер маникюра',
  /** @deprecated используйте ui.gallery.kicker */
  galleryKicker: (count: number) => ui.gallery.kicker(count),
  /** Раздел «Работы» на главной: работы приходят из GET /api/portfolio. */
  gallery: {
    title: 'Работы',
    /** Подпись над заголовком: с числом работ, когда оно известно и не ноль. */
    kicker: (total: number | null) => (total ? `Портфолио · ${total} фото` : 'Портфолио'),
    open: (alt: string) => `Открыть фото: ${alt}`,
    loadMore: 'Показать ещё',
    loadError: 'Не удалось загрузить работы',
    retry: 'Повторить',
    /** Работ пока нет: за текстом — ссылка на канал. */
    empty: 'Скоро здесь появятся работы. А пока свежие — в канале',
    /** Без JavaScript работы не загрузить: за текстом — ссылка на канал. */
    noScript: 'Работы загружаются при включённом JavaScript. Свежие — в канале',
  },
  reviewForms: ['отзыв', 'отзыва', 'отзывов'] as [string, string, string],
  reviewsKicker: (count: number) => `${count} ${plural(count, ui.reviewForms)}`,
  /** Переключатель темы в шапке (role="switch", включён — тёмная). */
  darkTheme: 'Тёмная тема',
  notFound: {
    title: 'Страница не найдена',
    text: 'Такой страницы нет. Вернитесь на главную.',
    cta: 'На главную',
  },
}
