import { useCallback, useEffect, useId, useMemo, useRef, useState } from 'react'
import { apiGet } from '../../api/client'
import type { components } from '../../api/schema'
import { site } from '../../content/site'
import { ui } from '../../content/ui'
import { track } from '../../lib/analytics'
import { Lightbox, type LightboxPhoto } from '../Lightbox/Lightbox'
import styles from './Gallery.module.css'

type PublicWork = components['schemas']['PublicWork']

/** Работ на странице: делится на 2, 3 и 4 колонки сетки. */
const PAGE_SIZE = 12

/** Ширины вариантов, которые отдаёт GET /api/portfolio/images/{fileId}/{width}.jpg. */
const WIDTHS = [480, 960, 1600] as const

const TILE_SIZES =
  '(min-width: 1264px) 280px, (min-width: 1024px) 25vw, (min-width: 768px) 33vw, 50vw'

function imageUrl(fileId: string, width: (typeof WIDTHS)[number]): string {
  return `/api/portfolio/images/${encodeURIComponent(fileId)}/${width}.jpg`
}

function imageSrcSet(fileId: string): string {
  return WIDTHS.map((w) => `${imageUrl(fileId, w)} ${w}w`).join(', ')
}

function toPhoto(work: PublicWork): LightboxPhoto {
  return {
    id: work.id,
    alt: work.alt,
    caption: work.caption,
    src: imageUrl(work.fileId, 1600),
    srcSet: imageSrcSet(work.fileId),
    width: work.width,
    height: work.height,
  }
}

/** Куда перевести фокус после загрузки, начатой человеком: плитка по номеру или кнопка под сеткой. */
type FocusTarget = { tile: number } | 'button'

/**
 * Раздел «Работы»: опубликованные работы из GET /api/portfolio по 12 штук. Пререндер в API не
 * ходит: в разметке заголовок и заглушки сетки, работы грузит браузер (первый рендер — уже
 * загрузка, поэтому пререндер и гидрация совпадают). Запрос в пути всегда один: повторный клик во
 * время загрузки ничего не делает, ответ после размонтирования отбрасывается.
 *
 * Под сеткой — одна и та же кнопка: «Показать ещё» или, после ошибки, «Повторить». Пока идёт
 * загрузка, она остаётся на месте (aria-disabled), поэтому фокус на ней не теряется. После
 * загрузки, начатой человеком, фокус переходит на первую новую работу (нет новых — на последнюю),
 * но только если он был на этой кнопке или потерян и лайтбокс закрыт.
 */
export function Gallery() {
  const [works, setWorks] = useState<PublicWork[]>([])
  const [total, setTotal] = useState<number | null>(null)
  const [loading, setLoading] = useState(true)
  /** Последний запрос не удался: уже показанные работы остаются, кнопка — «Повторить». */
  const [failed, setFailed] = useState(false)
  /** Загружено всё: «Показать ещё» не нужна. */
  const [complete, setComplete] = useState(false)
  const [index, setIndex] = useState<number | null>(null)

  /** Показанные работы для обработчика ответа: запрос один, поэтому меняются только в нём. */
  const shown = useRef<PublicWork[]>([])
  /** Запрос в пути; null — свободно. */
  const request = useRef<AbortController | null>(null)
  /** Номер следующей страницы (с нуля). */
  const nextPage = useRef(0)
  const focusTarget = useRef<FocusTarget | null>(null)
  const grid = useRef<HTMLUListElement>(null)
  const button = useRef<HTMLButtonElement>(null)
  const openedFrom = useRef<HTMLButtonElement | null>(null)
  const errorId = useId()

  const load = useCallback((byUser: boolean) => {
    if (request.current) return
    const controller = new AbortController()
    request.current = controller
    const page = nextPage.current
    setLoading(true)
    const run = async () => {
      try {
        const result = await apiGet('/api/portfolio', {
          query: { page, size: PAGE_SIZE },
          signal: controller.signal,
        })
        if (controller.signal.aborted) return
        // Между запросами мастер мог добавить или убрать работу, и страницы сдвинулись: уже
        // показанные не повторяем.
        const known = new Set(shown.current.map((w) => w.id))
        const fresh = result.items.filter((w) => !known.has(w.id))
        if (byUser && shown.current.length + fresh.length > 0) {
          focusTarget.current = {
            tile: fresh.length > 0 ? shown.current.length : shown.current.length - 1,
          }
        }
        if (fresh.length > 0) {
          shown.current = [...shown.current, ...fresh]
          setWorks(shown.current)
        }
        nextPage.current = page + 1
        setTotal(result.total)
        // Конец списка: страница неполная, дошли до total или страница не добавила ничего нового.
        setComplete(
          result.items.length < PAGE_SIZE ||
            (page + 1) * PAGE_SIZE >= result.total ||
            fresh.length === 0,
        )
        setFailed(false)
      } catch {
        // И сбой сети или ответ не 2xx, и ответ неожиданной формы: раздел не остаётся в загрузке.
        if (controller.signal.aborted) return
        if (byUser) focusTarget.current = 'button'
        setFailed(true)
      } finally {
        if (request.current === controller) {
          request.current = null
          setLoading(false)
        }
      }
    }
    void run()
  }, [])

  useEffect(() => {
    load(false)
    return () => {
      request.current?.abort()
      request.current = null
    }
  }, [load])

  // Фокус — после того как новые плитки попали в DOM. Не отнимаем его у лайтбокса и у того, что
  // человек успел выбрать сам, пока шла загрузка.
  useEffect(() => {
    const target = focusTarget.current
    if (target === null || loading) return
    focusTarget.current = null
    if (index !== null) return
    const active = document.activeElement
    if (active && active !== document.body && active !== button.current) return
    const element =
      target === 'button'
        ? button.current
        : grid.current?.querySelectorAll<HTMLButtonElement>('[data-tile]')[target.tile]
    element?.focus()
  })

  const photos = useMemo(() => works.map(toPhoto), [works])

  const open = (i: number, tile: HTMLButtonElement) => {
    openedFrom.current = tile
    setIndex(i)
    track({ name: 'gallery_open', index: i })
  }

  const close = () => {
    setIndex(null)
    openedFrom.current?.focus()
  }

  // Заглушки — на месте работ, которые сейчас придут: до первого ответа целая страница.
  const placeholders = !loading
    ? 0
    : total === null
      ? PAGE_SIZE
      : Math.max(0, Math.min(PAGE_SIZE, total - works.length))
  const showButton = failed || (works.length > 0 && !complete)
  const showError = failed && !loading

  return (
    <section id="gallery" className="section">
      <div className="container">
        <span className="kicker">{ui.gallery.kicker(total)}</span>
        <h2 className={styles.title}>{ui.gallery.title}</h2>
        {(works.length > 0 || placeholders > 0) && (
          <ul ref={grid} className={styles.grid} aria-busy={loading || undefined}>
            {works.map((work, i) => (
              <li key={work.id} className={styles.item}>
                <button
                  type="button"
                  className={styles.tile}
                  data-tile
                  data-cursor="photo"
                  aria-label={ui.gallery.open(work.alt)}
                  onClick={(e) => open(i, e.currentTarget)}
                >
                  <img
                    src={imageUrl(work.fileId, 480)}
                    srcSet={imageSrcSet(work.fileId)}
                    sizes={TILE_SIZES}
                    alt={work.alt}
                    loading="lazy"
                    decoding="async"
                    width={work.width}
                    height={work.height}
                  />
                </button>
              </li>
            ))}
            {Array.from({ length: placeholders }, (_, i) => (
              <li
                key={`placeholder-${i}`}
                className={styles.placeholder}
                data-placeholder
                aria-hidden
              />
            ))}
          </ul>
        )}
        <noscript>
          <p className={styles.note}>
            {ui.gallery.noScript} <a href={site.channelUrl}>{site.channelHandle}</a>
          </p>
        </noscript>
        {!loading && !failed && works.length === 0 && (
          <p className={styles.note}>
            {ui.gallery.empty}{' '}
            <a
              href={site.channelUrl}
              target="_blank"
              rel="noopener noreferrer"
              onClick={() => track({ name: 'contact_click', channel: 'channel' })}
            >
              {site.channelHandle}
            </a>
          </p>
        )}
        {showButton && (
          <div className={styles.footer}>
            {/* <output> — роль status, а не alert: ошибку загрузки, которую никто не запускал, не
                выкрикиваем; после действия человека фокус и так на кнопке, а текст связан с ней. */}
            {showError && (
              <output id={errorId} className={styles.note}>
                {ui.gallery.loadError}
              </output>
            )}
            {/* aria-disabled, а не disabled: кнопка с фокусом не теряет его на время загрузки */}
            <button
              ref={button}
              type="button"
              className="button"
              aria-disabled={loading || undefined}
              aria-describedby={showError ? errorId : undefined}
              onClick={() => load(true)}
            >
              {failed ? ui.gallery.retry : ui.gallery.loadMore}
            </button>
          </div>
        )}
      </div>
      <Lightbox photos={photos} index={index} onClose={close} onNavigate={setIndex} />
    </section>
  )
}
