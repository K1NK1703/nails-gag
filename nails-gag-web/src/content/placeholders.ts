/*
 * Реквизиты для юридических текстов. Заполнить до публикации:
 * `npm run check-dist` перечисляет незаполненные плейсхолдеры.
 * Вынесены в отдельный модуль без импортов, чтобы site.ts и privacy.ts
 * могли использовать их без циклической зависимости.
 */
export const OPERATOR_FIO = '{{OPERATOR_FIO}}'
export const OPERATOR_INN = '{{OPERATOR_INN}}'
export const OPERATOR_EMAIL = '{{OPERATOR_EMAIL}}'
export const OPERATOR_CITY = '{{OPERATOR_CITY}}'
export const HOSTING_PROVIDER = '{{HOSTING_PROVIDER}}'
export const DEVELOPER_FIO = '{{DEVELOPER_FIO}}'
export const SMS_PROVIDER = '{{SMS_PROVIDER}}'

/** Домен сайта из общего адреса (VITE_SITE_URL или адрес по умолчанию, см. scripts/site-url.ts). */
export const SITE_DOMAIN = new URL(__SITE_URL__).host

/** Все допустимые плейсхолдеры в юридических текстах. */
export const PLACEHOLDERS = [
  OPERATOR_FIO,
  OPERATOR_INN,
  OPERATOR_EMAIL,
  OPERATOR_CITY,
  HOSTING_PROVIDER,
  DEVELOPER_FIO,
  SMS_PROVIDER,
] as const
