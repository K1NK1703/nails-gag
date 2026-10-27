import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
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

/**
 * loading — запрос в пути; с него же начинается первый рендер, поэтому пререндер и гидрация
 * показывают заглушки, а запрос уходит из эффекта. error — последний запрос не удался, уже
 * показанные работы остаются.
 */
type Status = 'loading' | 'ready' | 'error'

/**
 * Раздел «Работы»: опубликованные работы из GET /api/portfolio по 12 штук. Пререндер в API не
 * ходит: в разметке заголовок и заглушки сетки, работы грузит браузер. Запрос в пути всегда один:
 * повторный клик «Показать ещё» во время загрузки ничего не делает, ответ после размонтирования
 * отбрасывается.
 */
export function Gallery() {
  const [works, setWorks] = useState<PublicWork[]>([])
  const [total, setTotal] = useState<number | null>(null)
  const [status, setStatus] = useState<Status>('loading')
  /** Загружено всё: кнопка «Показать ещё» не нужна. */
  const [complete, setComplete] = useState(false)
  const [index, setIndex] = useState<number | null>(null)

  /** Показанные работы для обработчика ответа: запрос один, поэтому меняются только в нём. */
  const shown = useRef<PublicWork[]>([])
  /** Запрос в пути; null — свободно. */
  const request = useRef<AbortController | null>(null)
  /** Номер следующей страницы (с нуля). */
  const nextPage = useRef(0)
  /** Первая новая работа после загрузки, начатой человеком: на неё переходит фокус. */
  const focusTile = useRef<number | null>(null)
  /** Загрузку, начатую человеком, постигла ошибка: фокус — на «Повторить». */
  const focusRetry = useRef(false)
  const tiles = useRef<(HTMLButtonElement | null)[]>([])
  const retry = useRef<HTMLButtonElement>(null)
  const openedFrom = useRef<HTMLButtonElement | null>(null)

  const load = useCallback((byUser: boolean) => {
    if (request.current) return
    const controller = new AbortController()
    request.current = controller
    const page = nextPage.current
    setStatus('loading')
    apiGet('/api/portfolio', { query: { page, size: PAGE_SIZE }, signal: controller.signal }).then(
      (result) => {
        if (controller.signal.aborted) return
        request.current = null
        nextPage.current = page + 1
        // Между запросами мастер мог добавить или убрать работу, и страницы сдвинулись: уже
        // показанные не повторяем.
        const known = new Set(shown.current.map((w) => w.id))
        const fresh = result.items.filter((w) => !known.has(w.id))
        if (fresh.length > 0) {
          if (byUser) focusTile.current = shown.current.length
          shown.current = [...shown.current, ...fresh]
          setWorks(shown.current)
        }
        setTotal(result.total)
        // Конец списка: страница неполная, дошли до total или страница не добавила ничего нового.
        setComplete(
          result.items.length < PAGE_SIZE ||
            (page + 1) * PAGE_SIZE >= result.total ||
            fresh.length === 0,
        )
        setStatus('ready')
      },
      () => {
        if (controller.signal.aborted) return
        request.current = null
        focusRetry.current = byUser
        setStatus('error')
      },
    )
  }, [])

  useEffect(() => {
    load(false)
    return () => {
      request.current?.abort()
      request.current = null
    }
  }, [load])

  // Фокус — после того как новые плитки попали в DOM.
  useEffect(() => {
    const first = focusTile.current
    if (first === null || first >= works.length) return
    focusTile.current = null
    tiles.current[first]?.focus()
  }, [works])

  useEffect(() => {
    if (status !== 'error' || !focusRetry.current) return
    focusRetry.current = false
    retry.current?.focus()
  }, [status])

  const photos = useMemo(() => works.map(toPhoto), [works])

  const open = (i: number, button: HTMLButtonElement) => {
    openedFrom.current = button
    setIndex(i)
    track({ name: 'gallery_open', index: i })
  }

  const close = () => {
    setIndex(null)
    openedFrom.current?.focus()
  }

  // Заглушки — на месте работ, которые сейчас придут: до первого ответа целая страница.
  const placeholders =
    status !== 'loading'
      ? 0
      : total === null
        ? PAGE_SIZE
        : Math.max(0, Math.min(PAGE_SIZE, total - works.length))

  return (
    <section id="gallery" className="section">
      <div className="container">
        <span className="kicker">{total ? ui.galleryKicker(total) : ui.gallery.kicker}</span>
        <h2 className={styles.title}>{ui.gallery.title}</h2>
        <ul className={styles.grid} aria-busy={status === 'loading' || undefined}>
          {works.map((work, i) => (
            <li key={work.id} className={styles.item}>
              <button
                ref={(el) => {
                  tiles.current[i] = el
                }}
                type="button"
                className={styles.tile}
                data-cursor="photo"
                aria-label={ui.gallery.open(work.alt)}
                onClick={(e) => open(i, e.currentTarget)}
              >
                <img
                  src={imageUrl(work.fileId, 480)}
                  srcSet={imageSrcSet(work.fileId)}
                  sizes={TILE_SIZES}
                  alt={work.alt}
                  loading={i < 2 ? 'eager' : 'lazy'}
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
        {status === 'ready' && works.length === 0 && (
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
        {status === 'error' && (
          <div className={styles.footer}>
            <p role="alert" className={styles.note}>
              {ui.gallery.loadError}
            </p>
            <button ref={retry} type="button" className="button" onClick={() => load(true)}>
              {ui.gallery.retry}
            </button>
          </div>
        )}
        {status !== 'error' && works.length > 0 && !complete && (
          <div className={styles.footer}>
            {/* aria-disabled, а не disabled: кнопка с фокусом не теряет его на время загрузки */}
            <button
              type="button"
              className="button"
              aria-disabled={status === 'loading' || undefined}
              onClick={() => load(true)}
            >
              {ui.gallery.loadMore}
            </button>
          </div>
        )}
      </div>
      <Lightbox photos={photos} index={index} onClose={close} onNavigate={setIndex} />
    </section>
  )
}
