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
- О мастере: `about.ts` (фото — `public/studio-{480,960,1600}.webp`).
- Работы: раздел «Работы» берёт их из бэкенда (`GET /api/portfolio`), их ведёт мастер — в коде фронта списка работ нет. Подписи раздела («Показать ещё», текст ошибки и пустого списка) — `ui.ts` (`gallery`).
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
- `npm run dev` проксирует `/api` на бэкенд `http://localhost:8080`: один origin, как за nginx в продакшене, поэтому cookie сессии и проверка `Origin` работают без CORS. Типы адресов — `src/api/schema.ts` (см. «HTTP API» в разделе бэкенда), запросы `GET` — через `src/api/client.ts` (`apiGet`: типы из схемы, код не из 2xx — `ApiError`, отмена через `AbortSignal`).
- Раздел «Работы» (`src/components/Gallery/`): пререндер в API не ходит — в разметке заголовок и заглушки сетки; браузер грузит `GET /api/portfolio?page=N&size=12`, «Показать ещё» добавляет следующую страницу и исчезает, когда загружено всё. Картинки — `/api/portfolio/images/{fileId}/{480|960|1600}.jpg` через `srcset`. Ошибка — «Не удалось загрузить работы» и «Повторить», уже показанные работы остаются; лайтбокс листает загруженные. Без бэкенда (`npm run dev` без `bootRun`) раздел показывает ошибку, остальная страница работает.
- Переходы между страницами: `TransitionLink` (View Transitions API, в браузерах без поддержки мгновенная смена). Роутер декларативный `BrowserRouter` с `useTransitions={false}`, иначе переход не успевает захватить новую страницу.
- Движение: reveal-появление секций (повторяется), hover-эффекты только под `@media (hover: hover)`, всё отключается при `prefers-reduced-motion`. Курсор-рука (`src/components/Cursor/`) только на устройствах с мышью; стиль ногтей — константа `NAIL_STYLE`.
- Шрифт Inter: два своих файла в `public/fonts/` (latin и cyrillic) с preload; знак ₽ берётся из системного шрифта, это сознательно, чтобы не грузить лишние 80 КБ.
- Стекло: токены `--glass-*` в `tokens.css`; размытие `backdrop-filter` только у шапки, меню, кнопки входа и оглавления документов.

### Как добавить фото

Работы добавляет мастер через бэкенд (`POST /api/files?purpose=portfolio`, затем `POST /api/portfolio/works`; экраны админки — отдельно), пересборка сайта не нужна. Статичные картинки сайта (герой, портрет, фото «О мастере», `og.jpg`) делает `npm run images` из исходников в `media/photos/` (`hero-cutout.png`, `studio.jpeg`, `IMG_4978.JPG` для `og.jpg`); результат — в `public/`, его коммитят.

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

Модули: `nails-gag-common` (разделы и уровни доступа, действующее лицо, исключения, время), `nails-gag-test-support` (тестовая обвязка), `nails-gag-app` (запуск, конфигурация, миграции). Доменные модули: `nails-gag-catalog` (услуги и дополнения, прайс), `nails-gag-clients` (клиенты, согласия и версии документов, признак предоплаты, объединение дублей, блокировка, обезличивание при удалении аккаунта), `nails-gag-schedule` (слоты, открытые для записи, шаблоны дня и недели, ротация недель, длительность слота с журналом; занятие/освобождение слотов для записей), `nails-gag-booking` (записи: создание, отмена и перенос по правилам оферты, предоплата с суммой и исходом, предложение приехать раньше, отметки визита с итогом и причиной суммы, пакетная отметка, записи клиентов, удаливших аккаунт, на рассмотрении мастера, денежные факты для финансов, фото-референсы к записи со сроком хранения, фоновые задачи, настройки правил), `nails-gag-files` (хранилище фото: проверка формата по содержимому и лимитов, уменьшение до вариантов в JPEG без метаданных с поворотом по EXIF, хранение на диске, отдача, ночная уборка непривязанных файлов и каталогов без строки в базе), `nails-gag-portfolio` (работы мастера: описание, подпись, порядок, видимость; публикация по источнику и согласию клиента, снятие с публикации при отзыве согласия), `nails-gag-finance` (финансы мастера: категории доходов и расходов, ручные записи с журналом изменений, быстрый ввод, повторяющиеся записи с ночной задачей, отчёты из ручных записей и денежных фактов записей), `nails-gag-identity` (вход через Яндекс ID и VK ID, аккаунты и способы входа, сессии в PostgreSQL, роли и личные переопределения прав с правилом последнего администратора, журналы входов и прав, реакция на блокировку и объединение клиентов, удаление аккаунта, ежедневная очистка просроченных сессий, билетов регистрации и старых событий входа). HTTP-слой — `nails-gag-api` (защита, единый формат ошибок, вход по HTTP, наблюдаемость запросов, адреса всех доменных модулей).

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

`nails-gag-api` публичного Java-API не имеет: в корне `gag.nails.api` только `package-info.java`, всё остальное — в `internal` (настройки модуля) и `internal.<область>` (`auth`, `security`, `error`, `http`, `observability`, `me`, `documents`, `catalog`, `clients`, `schedule`, `booking`, `files`, `portfolio`, `finance`, `access`).

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
- Клиенты: `/api/clients` — поиск постранично и новая карточка; `/api/clients/{id}` — карточка, телефоны, заметка, согласия, предоплата, блокировка, объединение, записи клиента (`?scope=active|history`); `DELETE /api/clients/{id}/consents/{kind}` — отзыв согласия (`photo-publication` и другие необязательные; основное и оферту отозвать нельзя — `409 clients.consent_required`).
- Расписание: `/api/schedule/free-slots` — свободные слоты для записи; `/api/schedule/slots`, `/day-templates`, `/week-templates`, `/days/apply-template`, `/rotation` — слоты, шаблоны и ротация; `/api/schedule/settings` — длительность слота и журнал.
- Записи: `/api/bookings` — период, очереди (`/awaiting-mark`, `/awaiting-prepayment`, `/under-review` — записи клиентов, удаливших аккаунт), запись клиентом и мастером (`/by-master`), `POST /api/bookings/complete-as-planned` — пакетная отметка «выполнены по прайсу» (`{ "bookingIds" }`, до 50; ответ `200` с `{ bookingId, errorCode }` по каждой записи); `/api/bookings/{id}/…` — отмена, отказ опоздавшему, перенос, комментарий, заметка, предоплата (`prepayment/received` с необязательным `{ "amount" }`, `PUT prepayment/outcome` — `{ "outcome": "KEPT" | "RETURNED" }`), предложение приехать раньше, отметки визита (`PUT visit` — состав, итог и причина суммы); `/api/booking-settings` — правила записи и журнал.
- Файлы: `POST /api/files?purpose=reference|portfolio` (multipart, поле `file`) — загрузка фото: `reference` — любой вошедший, `portfolio` — с изменением «Портфолио». Файл до привязки — «сирота»: его прикладывают к записи или работе, иначе ночная уборка удаляет его через сутки.
- Портфолио: `GET /api/portfolio?page=&size=` — опубликованные работы без входа (по умолчанию 12 на странице, кэш 5 минут); `GET /api/portfolio/images/{fileId}/{480|960|1600}.jpg` — картинка опубликованной работы (публично, `Cache-Control: public, max-age=3600`, `ETag`/`304`; неопубликованная — только с чтением «Портфолио», `no-store`, иначе `404`); `/api/portfolio/works` — все работы со скрытыми и согласием, добавление, правка, `…/{id}/show` и `…/hide`, удаление; `PUT /api/portfolio/order` — порядок.
- Референсы записи: `/api/bookings/{id}/references` — список и добавление (`{ "fileId" }`), `DELETE …/references/{fileId}`, `GET …/references/{fileId}.jpg` (`no-store`). До 3 на запись; клиент добавляет и удаляет до начала визита, мастер — всегда.
- Финансы (чтение — чтение «Финансов», изменение — изменение; клиенту — `404`; ответы `no-store`): `/api/finance/categories?entryKind=income|expense` — категории вида, создание, переименование (`PATCH`), `…/{id}/hide` и `…/show`, `PUT …/order` (`{ "kind": "INCOME", "orderedIds": […] }`); `/api/finance/entries?from=&to=&entryKind=&categoryId=&page=&size=` — ручные доходы и расходы постранично, внесение, `GET`/`PATCH`/`DELETE …/{id}`, `…/{id}/history` — журнал изменений, `…/recent?entryKind=` — быстрый ввод; `/api/finance/recurring` — шаблоны повторяющихся записей, `…/{id}/pause` и `…/resume`; `/api/finance/summary`, `/monthly?year=`, `/services` — отчёты, `/api/finance/journal?from=&to=&type=visit&type=kept-prepayment&categoryId=&page=&size=` — журнал операций постранично (`type` повторяется или через запятую: `type=visit,income`). Перечисления в адресе — через дефис (`entryKind`, а не `kind`: это имя занято видом документа), в JSON — имена Java.
- Доступ (страница разработчика): `/api/access/roles` — роли; `/api/access/staff` и `/api/access/accounts/{id}` — права, назначение ролей и личные переопределения (`…/overrides/booking-settings`); `/api/access/journal` и `/api/access/login-journal` — журналы постранично (`?page=&size=`).

Фото (модули `files`, `portfolio`, референсы в `booking`):

- Хранилище — каталог `nails-gag.files.root`: варианты лежат как `<root>/<portfolio|reference>/<id>/<ширина>.jpg`, оригинал не хранится. По умолчанию `/var/lib/nails-gag/files` (в образе — каталог под том, см. `Dockerfile`), в профиле `local` — `nails-gag-backend/build/files` (`./gradlew clean` его не трогает: у корневого проекта нет `clean`). Работы — 480, 960 и 1600 px, референсы — 2000 px; вариант не шире оригинала.
- Лимиты: файл до 15 МБ (`nails-gag.files.max-file-bytes`, multipart `max-file-size` 15 МБ и `max-request-size` 16 МБ; больше — `413 files.too_large_file`), картинка до 50 Мп (`max-pixels`; WebP без потерь и с прозрачностью — до 16 Мп; больше — `400 files.too_large_image`), форматы JPEG, PNG и WebP по содержимому (иначе `400 files.unsupported_format`); одновременно обрабатываются 2 фото (`max-concurrent-processing`), остальные ждут до 10 с (`processing-wait`), затем `503 files.busy`. Загрузки — не больше 30 в час на аккаунт (`nails-gag.api.rate-limits.uploads-per-hour`), картинки портфолио — своя корзина 600 в минуту на IP (`images-per-minute`).
- Публикация: работа видна на сайте, если мастер её показывает и источник — `CLIENT_OUTSIDE_SITE` (согласие получено вне сайта) или `EXAMPLE`, либо `CLIENT` и у клиента есть согласие `PHOTO_PUBLICATION`. Отзыв согласия сразу снимает все работы клиента; копии картинок в кэшах живут не дольше часа.
- Сроки хранения: референсы удаляются вместе с файлами через 30 дней после визита или отмены записи (`nails-gag.booking.reference-retention-days`, ночная задача `reference-cleanup-cron`); непривязанные файлы и каталоги без строки в базе — через сутки (`nails-gag.files.jobs.cleanup-cron`). Обезличивание клиента удаляет его работы и референсы вместе с файлами.

Перенос нынешней галереи в портфолио (разово, только профиль `local`; спецификация §9). Список — JSON рядом с исходниками в `media/photos/` (не в git): `[{ "file": "IMG_4241.JPG", "alt": "…", "caption": null }]`, пути — относительно файла списка, порядок — порядок на сайте. Импорт действует от имени первого незаблокированного аккаунта с правом записи в «Портфолио», поэтому на свежей базе сначала один раз войти через подставного провайдера с `subject` из `bootstrap-developers` (вход по HTTP — в «Профилях»):

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew :nails-gag-app:bootRun --args='--nails-gag.identity.bootstrap-developers=FAKE:local-developer'
# войти как FAKE:local-developer, остановить приложение, затем:
SPRING_PROFILES_ACTIVE=local ./gradlew :nails-gag-app:bootRun --args='--nails-gag.portfolio-import.source=../media/photos/gallery.json'
```

Путь относительный от `nails-gag-backend/` (там работает `bootRun`). Сначала проверяется весь список: у каждой записи есть `file` и `alt`, файл существует, описание и подпись не длиннее 200 символов, лишних полей нет; ошибка называет номер записи, и ничего не загружается. Затем каждое фото загружается в хранилище и становится видимой работой `CLIENT_OUTSIDE_SITE` в конце портфолио; в логе — `portfolio import from …: N entries as account …` и `… N imported, M skipped`. Повторный запуск ничего не дублирует: фото с тем же содержимым (SHA-256), уже ставшее работой, пропускается; но работу, которую мастер удалил, повторный импорт вернёт. Пустой `nails-gag.portfolio-import.source` (по умолчанию) — импорт не запускается.

На проде импорта нет (бин только в профиле `local`): работы заводятся через админку, перенос на прод — дело запуска. Список нынешней галереи с описаниями и подписями, если `media/photos/gallery.json` потерян, восстанавливается из истории: `git show 6fc4b88^:nails-gag-web/src/content/gallery.ts` (файлы — по парам `WORKS` в `git show 6fc4b88^:nails-gag-web/scripts/images.ts`).

Финансы (модуль `finance`, деньги записей в `booking`):

- Доход от визитов не копируется: отчёты читают итоги выполненных визитов и удержанные предоплаты из `booking` и складывают с ручными записями `finance`. Клиенту деньги не показываются: в его записях `finalAmount`, `amountNote`, `prepaymentAmount`, `prepaymentOutcome` — `null`.
- Итог визита — как по прайсу, со скидкой, бесплатно или больше прайса, с необязательной причиной суммы (до 200 символов); скидки и надбавки считает отчёт: прайс фактического состава минус итог.
- Предоплата: сумма вводится при «получена», по умолчанию — процент из правил записи от прайса, до рубля, не меньше рубля. Исход вычисляется: `APPLIED` — запись активна или выполнена (в счёт визита); `KEPT` — неявка, поздняя отмена клиентом, отказ опоздавшему, отмена мастером записи клиента, удалившего аккаунт; `RETURNED` — отмена в окне и мастером. Мастер может переопределить «удержана ↔ возвращена» в пределах срока исправления отметок (`mark_correction_window_days`, по умолчанию 7 дней) от отмены или неявки; значение, совпадающее с правилом, снимает переопределение.
- Удаление аккаунта запись не отменяет: активные записи до начала визита остаются, слот занят, у записи — `clientErasedAt`, она в `/api/bookings/under-review`. Мастер оставляет её (после начала визита — обычная отметка, неявка — предоплата удержана) или отменяет (причина `ACCOUNT_DELETED`, предоплата удержана).
- Категории — свои для доходов и расходов, стартовый набор из миграции; не удаляются, а скрываются; название уникально в виде без учёта регистра. Ручная запись: сумма больше нуля, день — с `2000-01-01` до сегодня плюс 366 дней; каждое создание, изменение и удаление пишется в журнал (было → стало).
- Повторяющиеся записи: шаблон создаёт запись в своё число каждого месяца (в коротком месяце — в последний день) с `startsOn` по `endsOn`, не на паузе. Задача — `nails-gag.finance.jobs.recurring-cron` (по умолчанию `0 15 0 * * *` по времени салона) и ещё раз сразу после старта приложения, выключается `nails-gag.finance.jobs.enabled=false`. Прогресс шаблона — `finance.recurring.generated_through` (по какой день записи уже созданы): пропущенные дни догоняются, повтор ничего не дублирует, созданная запись правится и удаляется как обычная и заново не появляется; дни паузы не догоняются. Создание и правка шаблона сразу создают записи за наступившие дни.
- Отчёты: сводка за период (визиты — число, прайс, скидки, надбавки, получено, средний чек; удержанные предоплаты; ручные доходы и расходы по категориям; доход, расход, прибыль), помесячно за год (12 месяцев), по услугам фактического состава, журнал операций (визиты, удержанные предоплаты, ручные записи; новые дни первыми). Период — дни в поясе салона, не длиннее 366 дней и не дальше года вперёд.
- База для запуска создаётся с UTF-8 `LC_CTYPE` (например, `C.UTF-8` или `ru_RU.UTF-8`, как в официальном образе PostgreSQL): уникальность названий категорий без учёта регистра (`lower(name)`) для кириллицы зависит от ctype базы.

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
