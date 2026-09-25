# nails.gag

Сайт мастера маникюра nails.gag (Санкт-Петербург). Монорепозиторий.

| Папка | Что это |
|---|---|
| `nails-gag-web/` | лендинг: Vite + React + TypeScript, статическая сборка с пререндером |
| `media/` | исходные фото (не в git) |
| `docs/` | спецификации и планы (не в git) |

## Лендинг

```bash
cd nails-gag-web
nvm use            # Node из .nvmrc
npm install
npm run dev        # http://localhost:5173
npm test           # Vitest
npm run lint       # oxlint
npm run format     # prettier
npm run build      # dist/: клиент + пререндер главной и /privacy
npm run check-dist # smoke-проверка результата сборки
npm run preview    # посмотреть dist/ на http://localhost:4173
```

### Переменные окружения

Скопируйте `.env.example` в `.env`:

- `VITE_FEATURE_CABINET` — `true`, чтобы показать пункт «Личный кабинет» и маршрут `/cabinet`.
- `VITE_ANALYTICS_ENDPOINT` — адрес для событий аналитики; пусто — события не отправляются.
- `VITE_SITE_URL` — публичный адрес сайта для canonical, Open Graph, sitemap и robots.

### Как поменять контент

Все тексты, цены, вопросы, отзывы и контакты лежат в `nails-gag-web/src/content/`. Компоненты текстов не содержат.

- Цены: `prices.ts`.
- Отзывы: `reviews.ts`. Пока стоят примеры (`isExample: true`); замените текст и поставьте `false`, подпись «пример» исчезнет.
- Частые вопросы: `faq.ts`.
- О мастере: `about.ts`.
- Контакты и адрес: `site.ts`. Telegram (`telegramUrl`, `channelUrl`) выводится только в разделе «Контакты», это вторичный канал связи.
- Запись: все кнопки «Записаться» (шапка, первый экран, прайс) ведут на `/booking` (`site.bookingPath`). Пока это заглушка `src/pages/Booking.tsx`, форма появится вместе с бэкендом и личным кабинетом.
- Политика обработки персональных данных: `privacy.ts` (страница `/privacy`). **До публикации заполните плейсхолдеры** в начале файла: `OPERATOR_FIO`, `OPERATOR_INN`, `OPERATOR_EMAIL`, `OPERATOR_CITY`, `HOSTING_PROVIDER`, `DEVELOPER_FIO`, `SMS_PROVIDER`; домен берётся из `VITE_SITE_URL`. `npm run check-dist` перечисляет незаполненные `{{…}}` предупреждением.
- Тексты согласий для будущих форм записи и регистрации: `consents.ts` (основное, публикация фото, напоминания, рассылки — каждое отдельной галочкой).
- Что сделать до запуска (уведомления в Роскомнадзор, договор поручения, обязательство о неразглашении): [`legal/README.md`](legal/README.md).

### Как добавить фото

1. Положите файл в `media/photos/`.
2. Добавьте пару `[имя файла без расширения, 'work-NN']` в `WORKS` в `nails-gag-web/scripts/images.ts`.
3. Добавьте запись с тем же `slug` и alt-текстом в `src/content/gallery.ts`.
4. `npm run images`, закоммитьте `public/gallery/`.

### Деплой

`npm run build` даёт папку `dist/`, её раздаёт любой статический сервер. Для каждой пререндеренной страницы есть и `privacy/index.html`, и `privacy.html`, поэтому подходят оба типа конфигураций. Для маршрутов без пререндера (`/cabinet`, 404) нужен fallback на `index.html`.

Пример для Caddy:

```
nails-gag.ru {
    root * /srv/nails-gag/dist
    encode zstd gzip
    try_files {path} {path}.html {path}/index.html /index.html
    file_server
}
```
