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
  galleryKicker: (count: number) => `Портфолио · ${count} фото`,
  reviewForms: ['отзыв', 'отзыва', 'отзывов'] as [string, string, string],
  reviewsKicker: (count: number) => `${count} ${plural(count, ui.reviewForms)}`,
  notFound: {
    title: 'Страница не найдена',
    text: 'Такой страницы нет. Вернитесь на главную.',
    cta: 'На главную',
  },
}
