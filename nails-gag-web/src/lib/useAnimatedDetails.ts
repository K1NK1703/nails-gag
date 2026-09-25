import { useCallback, useRef } from 'react'
import type { MouseEvent, RefObject } from 'react'

const DURATION = 280
const EASING = 'cubic-bezier(0.2, 0.7, 0.2, 1)'

function canAnimate(el: HTMLElement): boolean {
  if (typeof el.animate !== 'function') return false
  if (typeof window.matchMedia !== 'function') return true
  return !window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

/** Высота свёрнутого <details>: summary + вертикальные padding и border. */
function collapsedHeight(el: HTMLDetailsElement, summary: HTMLElement): number {
  const cs = getComputedStyle(el)
  const extra = ['paddingTop', 'paddingBottom', 'borderTopWidth', 'borderBottomWidth'] as const
  return extra.reduce((h, key) => h + (parseFloat(cs[key]) || 0), summary.offsetHeight)
}

/**
 * Плавное раскрытие нативного <details>: возвращает onClick для <summary>.
 * Без Web Animations API или при prefers-reduced-motion просто переключает open.
 * Событие toggle срабатывает как у обычного <details> — один раз на смену open.
 */
export function useAnimatedDetails(ref: RefObject<HTMLDetailsElement | null>) {
  const animation = useRef<Animation | null>(null)
  const closing = useRef(false)

  return useCallback(
    (e: MouseEvent<HTMLElement>) => {
      const el = ref.current
      if (!el) return
      e.preventDefault()

      if (!canAnimate(el)) {
        el.open = !el.open
        return
      }

      const summary = e.currentTarget
      const from = el.offsetHeight
      const opening = !el.open || closing.current
      animation.current?.cancel()
      closing.current = !opening

      let to: number
      if (opening) {
        el.open = true
        to = el.offsetHeight
      } else {
        to = collapsedHeight(el, summary)
      }

      el.style.overflow = 'hidden'
      const anim = el.animate(
        { height: [`${from}px`, `${to}px`] },
        { duration: DURATION, easing: EASING },
      )
      animation.current = anim
      anim.onfinish = () => {
        if (closing.current) el.open = false
        closing.current = false
        animation.current = null
        el.style.overflow = ''
      }
      // cancel приходит асинхронно: если уже запущена новая анимация, не трогаем её overflow.
      anim.oncancel = () => {
        if (animation.current !== anim) return
        animation.current = null
        el.style.overflow = ''
      }
    },
    [ref],
  )
}
