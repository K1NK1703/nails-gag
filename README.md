# nails.gag

Сайт мастера маникюра nails.gag (Санкт-Петербург). Монорепозиторий.

| Папка | Что это |
|---|---|
| `nails-gag-web/` | лендинг: Vite + React + TypeScript, статическая сборка с пререндером |
| `nails-gag-backend/` | бэкенд: Java 25, Spring Boot 4, модульный монолит, PostgreSQL |
| `media/` | исходные фото (не в git) |
| `legal/` | памятка по Роскомнадзору и шаблон договора поручения обработки |
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
npm run build      # dist/: клиент + пререндер /, /privacy, /terms, /booking, /cabinet
npm run check-dist # smoke-проверка результата сборки
npm run preview    # посмотреть dist/ на http://localhost:4173
```

### Переменные окружения

Скопируйте `.env.example` в `.env`:

- `VITE_FEATURE_CABINET` — кабинет включён по умолчанию (кнопка «Войти» в шапке и маршрут `/cabinet`); `false` прячет.
- `VITE_PREVIEW_USER` — имя «вошедшего» пользователя, только чтобы посмотреть дизайн шапки для вошедшего (`useAuth` пока заглушка).
- `VITE_ANALYTICS_ENDPOINT` — адрес для событий аналитики; пусто — события не отправляются.
- `VITE_SITE_URL` — публичный адрес сайта для canonical, Open Graph, sitemap, robots и текстов документов (по умолчанию `https://nails-gag.ru`).

### Как поменять контент

Все тексты, цены, вопросы, отзывы и контакты лежат в `nails-gag-web/src/content/`. Компоненты текстов не содержат.

- Цены: `prices.ts`.
- Отзывы: `reviews.ts`. Пока стоят примеры (`isExample: true`); замените текст и поставьте `false`, подпись «пример» исчезнет.
- Частые вопросы: `faq.ts`.
- О мастере: `about.ts`.
- Контакты и адрес: `site.ts`. Telegram (`telegramUrl`, `channelUrl`) выводится только в разделе «Контакты», это вторичный канал связи.
- Запись: все кнопки «Записаться» (шапка, первый экран, прайс) ведут на `/booking` (`site.bookingPath`). Пока это заглушка `src/pages/Booking.tsx`, форма появится вместе с бэкендом и личным кабинетом.
- Политика обработки персональных данных: `privacy.ts` (страница `/privacy`).
- Пользовательское соглашение (правила записи, отмены, оплаты, гарантии; оферта): `terms.ts` (страница `/terms`). Обе страницы рендерит общий компонент `src/components/LegalDocument/`: заголовок, версия, оглавление, разделы. Новая редакция — поднять `version` и `updated`.
- Подвал: ссылки на оба документа и строка об исполнителе `site.performerLine` («Исполнитель: самозанятая ФИО, ИНН …») из `site.ts`.
- **Плейсхолдеры реквизитов** объявлены в `placeholders.ts` (их же реэкспортирует `privacy.ts`): `OPERATOR_FIO`, `OPERATOR_INN`, `OPERATOR_EMAIL`, `OPERATOR_CITY`, `HOSTING_PROVIDER`, `DEVELOPER_FIO`, `SMS_PROVIDER`; домен берётся из `VITE_SITE_URL`. Они подставляются в политику, соглашение, согласия и строку исполнителя в подвале. **До публикации заполните их**: `npm run check-dist` перечисляет незаполненные `{{…}}` на `/privacy` и `/terms` предупреждением.
- Тексты согласий для будущих форм записи и регистрации: `consents.ts` (основное, публикация фото, публикация отзыва, напоминания, рассылки — каждое отдельной галочкой).
- Что сделать до запуска (уведомления в Роскомнадзор, договор поручения с разработчиком, сбор отзывов): [`legal/README.md`](legal/README.md).
- Общие строки интерфейса (подписи «Версия», «Содержание», склонения «отзыв/отзыва/отзывов», тексты 404): `ui.ts`. Заглушки страниц: `booking.ts`, `cabinet.ts`.

### Как устроен фронт

- Страницы: `/` (лендинг), `/privacy`, `/terms`, `/booking` и `/cabinet` (заглушки, `noindex`, не в sitemap), 404. Пререндер пишет `x/index.html` и `x.html` и помечает `#root` атрибутом `data-route`; клиент гидрирует только совпадающий маршрут, иначе рендерит заново.
- `npm run dev` проксирует `/api` на бэкенд `http://localhost:8080`: один origin, как за nginx в продакшене, поэтому cookie сессии и проверка `Origin` работают без CORS. Типы адресов — `src/api/schema.ts` (см. «HTTP API» в разделе бэкенда).
- Переходы между страницами: `TransitionLink` (View Transitions API, в браузерах без поддержки мгновенная смена). Роутер декларативный `BrowserRouter` с `useTransitions={false}`, иначе переход не успевает захватить новую страницу.
- Движение: reveal-появление секций (повторяется), hover-эффекты только под `@media (hover: hover)`, всё отключается при `prefers-reduced-motion`. Курсор-рука (`src/components/Cursor/`) только на устройствах с мышью; стиль ногтей — константа `NAIL_STYLE`.
- Шрифт Inter: два своих файла в `public/fonts/` (latin и cyrillic) с preload; знак ₽ берётся из системного шрифта, это сознательно, чтобы не грузить лишние 80 КБ.
- Стекло: токены `--glass-*` в `tokens.css`; размытие `backdrop-filter` только у шапки, меню, кнопки входа и оглавления документов.

### Как добавить фото

1. Положите файл в `media/photos/`.
2. Добавьте пару `[имя файла без расширения, 'work-NN']` в `WORKS` в `nails-gag-web/scripts/images.ts`.
3. Добавьте запись с тем же `slug` и alt-текстом в `src/content/gallery.ts`.
4. `npm run images`, закоммитьте `public/gallery/`.

### Деплой

`npm run build` даёт папку `dist/`, её раздаёт любой статический сервер. Для каждой пререндеренной страницы есть и `privacy/index.html`, и `privacy.html` (так же для `/terms`, `/booking`, `/cabinet`), поэтому подходят оба типа конфигураций. Fallback на `index.html` нужен только для несуществующих адресов (404).

Пример для Caddy:

```
nails-gag.ru {
    root * /srv/nails-gag/dist
    encode zstd gzip
    try_files {path} {path}.html {path}/index.html /index.html
    file_server
}
```

## Бэкенд

```bash
cd nails-gag-backend
./gradlew check                                   # тесты, границы модулей, форматирование (нужен Docker)
SPRING_PROFILES_ACTIVE=local ./gradlew :nails-gag-app:bootRun   # поднимает PostgreSQL через Compose, API на :8080, http://localhost:8081/actuator/health
./gradlew spotlessApply                           # форматирование
```

Модули: `nails-gag-common` (разделы и уровни доступа, действующее лицо, исключения, время), `nails-gag-test-support` (тестовая обвязка), `nails-gag-app` (запуск, конфигурация, миграции). Доменные модули: `nails-gag-catalog` (услуги и дополнения, прайс), `nails-gag-clients` (клиенты, согласия и версии документов, признак предоплаты, объединение дублей, блокировка, обезличивание при удалении аккаунта), `nails-gag-schedule` (слоты, открытые для записи, шаблоны дня и недели, ротация недель, длительность слота с журналом; занятие/освобождение слотов для записей), `nails-gag-booking` (записи: создание, отмена и перенос по правилам оферты, предоплата, предложение приехать раньше, отметки визита, фоновые задачи, настройки правил), `nails-gag-identity` (вход через Яндекс ID и VK ID, аккаунты и способы входа, сессии в PostgreSQL, роли и личные переопределения прав с правилом последнего администратора, журналы входов и прав, реакция на блокировку и объединение клиентов, удаление аккаунта, ежедневная очистка просроченных сессий, билетов регистрации и старых событий входа). HTTP-слой — `nails-gag-api` (защита, единый формат ошибок, вход по HTTP, наблюдаемость запросов, адреса всех доменных модулей).

### Пакеты модуля

Каждый доменный модуль `gag.nails.<m>` разложен по роли, а `internal` — по подобласти. Границы проверяет `ModularityTest` (Spring Modulith).

| Пакет | Что лежит | Доступ из других модулей |
|---|---|---|
| `gag.nails.<m>` | интерфейсы сервисов и запросов, `package-info.java` | публичный API |
| `…model` | представления (`…View`), перечисления, записи-значения | `@NamedInterface("model")` |
| `…command` | команды (`Create…`, `Update…`, `Record…`, …) | `@NamedInterface("command")` |
| `…event` | события модуля | `@NamedInterface("event")` |
| `…error` | исключения, видимые пользователю | `@NamedInterface("error")` |
| `…internal.<область>` | сущности, репозитории, реализации одной подобласти (`clients.internal.consent`, `booking.internal.settings`, …) | закрыто |
| `…internal.error` | внутренние исключения | закрыто |

Подпакет без содержимого не создаётся (`command`/`event` есть не у всех модулей; `catalog` с одной подобластью оставляет `internal` плоским). Внутри `internal` подпакеты только по подобласти, не по типу классов; класс, нужный соседней подобласти, становится `public`, остальные остаются package-private.

`nails-gag-api` публичного Java-API не имеет: в корне `gag.nails.api` только `package-info.java`, всё остальное — в `internal` (настройки модуля) и `internal.<область>` (`auth`, `security`, `error`, `http`, `observability`, `me`, `documents`, `catalog`, `clients`, `schedule`, `booking`, `access`).

### HTTP API

- Все адреса под `/api`, JSON в `camelCase`. Ошибки — `application/problem+json` с машинным полем `code` (`common.not_found`, `booking.booking_not_found`, …) и `params`: по ним фронт выбирает текст.
- Вошедшего узнают по cookie `ng_session` (`HttpOnly`, `SameSite=Lax`, `Path=/api`). Права проверяют модули через `Actor`; HTTP-слой решает только, нужна ли сессия: нет её — `401`, у сотрудника нет прав — `403`, клиенту чужое — `404`.
- Изменяющие запросы требуют заголовок `X-Requested-With: nails.gag` и `Origin` (или `Referer`) сайта из `nails-gag.api.site-origin`, иначе `403 common.csrf`. Частые запросы — `429` с `Retry-After`; лимиты и остальные настройки — `nails-gag.api.*` в `application.yaml`.
- Actuator — на отдельном порту 8081, через прокси наружу не публикуется.
- Схема OpenAPI и Swagger UI — только при `bootRun` с профилем `local`: http://localhost:8080/api/openapi.json и http://localhost:8080/api/docs. springdoc подключён как `developmentOnly` и в `app.jar` и образ не попадает.
- Типы TypeScript для фронта — `nails-gag-web/src/api/schema.ts`, генерируются из схемы и коммитятся. Перегенерировать после каждого изменения адресов, тел запросов или представлений модулей: запустить бэкенд с профилем `local` и выполнить `npm run api-types` в `nails-gag-web`. `OpenApiSchemaTest` следит, что в схеме есть каждый адрес контроллеров.

Адреса по областям:

- Вход и сессия: `/api/auth/{provider}/start` и `/callback` (`yandex`, `vk`, в `local` — `fake`), `/api/auth/registration`, `/api/auth/link/confirm`, `/api/auth/logout`, `/api/auth/logout-all`; `/api/me` — вошедший и удаление аккаунта.
- Документы: `/api/documents` — действующие редакции политики и оферты, `/api/documents/{kind}/versions` — все редакции и новая.
- Каталог: `/api/price-list` — прайс без входа; `/api/services` — услуги, порядок, правка, скрытие и возврат.
- Клиенты: `/api/clients` — поиск постранично и новая карточка; `/api/clients/{id}` — карточка, телефоны, заметка, согласия, предоплата, блокировка, объединение, записи клиента (`?scope=active|history`).
- Расписание: `/api/schedule/free-slots` — свободные слоты для записи; `/api/schedule/slots`, `/day-templates`, `/week-templates`, `/days/apply-template`, `/rotation` — слоты, шаблоны и ротация; `/api/schedule/settings` — длительность слота и журнал.
- Записи: `/api/bookings` — период, очереди (`/awaiting-mark`, `/awaiting-prepayment`), запись клиентом и мастером (`/by-master`); `/api/bookings/{id}/…` — отмена, отказ опоздавшему, перенос, комментарий, заметка, предоплата, предложение приехать раньше, отметки визита; `/api/booking-settings` — правила записи и журнал.
- Доступ (страница разработчика): `/api/access/roles` — роли; `/api/access/staff` и `/api/access/accounts/{id}` — права, назначение ролей и личные переопределения (`…/overrides/booking-settings`); `/api/access/journal` и `/api/access/login-journal` — журналы постранично (`?page=&size=`).

Как найти запросы человека: каждый запрос пишет строку `"{метод} {шаблон адреса} -> {статус} {мс}ms"` (без строки запроса), а у вошедшего — `accountId` в MDC и атрибут `account.id` в span запроса. В Grafana (профиль `observability`): Tempo — запрос `{ span.account.id = "…" }` даёт все запросы человека по времени с SQL внутри, Loki — фильтр по полю `accountId`; `traceId` из тела ошибки ведёт к конкретному запросу.

### Профили

- `local` — читаемые логи, база из `compose.yaml`, вход через подставного провайдера (`nails-gag.identity.fake-provider.enabled=true`: профиль задаётся кодом через `gag.nails.identity.testing.FakeProvider`, без Яндекса и VK). Вход по HTTP: `POST /api/auth/fake/start` с `{"purpose":"LOGIN"}` → `POST /api/auth/fake/code` с профилем (`subject`, `phone`, `displayName`, …) → `GET /api/auth/fake/callback?code=…&state=…` (`state` — из `authorizationUri`, с cookie `ng_login`); новый человек затем отправляет `POST /api/auth/registration` с cookie `ng_ticket` и телом `firstName`, `phone`, `policyVersion`. Запросы `POST` — с заголовками `X-Requested-With: nails.gag` и `Origin: http://localhost:5173`.
- `observability` — экспорт трейсов, метрик и логов в `grafana/otel-lgtm`: `docker compose --profile observability up -d`, затем `SPRING_PROFILES_ACTIVE=local,observability ./gradlew :nails-gag-app:bootRun`, Grafana на http://localhost:3000 (admin/admin).
- Без профилей — как в продакшене: логи в JSON, база из `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`.
- `prod` — для боевого запуска: включает проверки настроек `FakeProviderGuard` (подставной провайдер запрещён) и `ApiPropertiesGuard` (нужен `nails-gag.api.login-state-key`, cookie только `Secure`); при нарушении приложение не стартует.

### Образ

```bash
./gradlew :nails-gag-app:bootJar && docker build -t nails-gag-backend .
```

CI (`.github/workflows/backend.yml`) собирает и публикует образ в `ghcr.io/k1nk1703/nails-gag/backend` на каждый push в `main`.
