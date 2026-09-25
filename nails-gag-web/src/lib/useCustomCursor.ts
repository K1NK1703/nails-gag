import { useEffect, useRef, useState } from 'react'

export type CursorState = 'default' | 'link' | 'photo' | 'press'

const FINE_POINTER = '(hover: hover) and (pointer: fine)'
const REDUCED_MOTION = '(prefers-reduced-motion: reduce)'
const LINK = 'a, button, [role=button], summary, label'
const PHOTO = '[data-cursor="photo"]'
const FIELD = 'input, textarea, select, [contenteditable]:not([contenteditable="false"])'
const EASE = 0.3
const EPSILON = 0.1

function canUseCustomCursor() {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return false
  return window.matchMedia(FINE_POINTER).matches && !window.matchMedia(REDUCED_MOTION).matches
}

function hoverStateOf(target: EventTarget | null): { state: CursorState; field: boolean } {
  if (!(target instanceof Element)) return { state: 'default', field: false }
  if (target.closest(FIELD)) return { state: 'default', field: true }
  const link = target.closest(LINK)
  const photo = target.closest(PHOTO)
  // Побеждает ближайший к цели элемент: кнопки внутри лайтбокса — «link», сама плитка — «photo».
  if (link && (!photo || (photo !== link && photo.contains(link)))) {
    return { state: 'link', field: false }
  }
  return { state: photo ? 'photo' : 'default', field: false }
}

/**
 * Кастомный курсор-«рука с маникюром». Включается только на устройствах с hover и точным
 * указателем и без prefers-reduced-motion; на сервере и на тач-устройствах enabled = false,
 * слушатели не вешаются. Позиция пишется прямо в style.transform элемента (без ре-рендеров),
 * с инерцией через requestAnimationFrame; цикл останавливается, когда курсор догнал цель.
 */
export function useCustomCursor() {
  const ref = useRef<HTMLDivElement>(null)
  const [enabled, setEnabled] = useState(false)
  const [hover, setHover] = useState<CursorState>('default')
  const [pressed, setPressed] = useState(false)
  const [overField, setOverField] = useState(false)
  const [moved, setMoved] = useState(false)
  const [inside, setInside] = useState(true)

  useEffect(() => {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return
    const queries = [window.matchMedia(FINE_POINTER), window.matchMedia(REDUCED_MOTION)]
    const update = () => setEnabled(canUseCustomCursor())
    update()
    for (const q of queries) q.addEventListener?.('change', update)
    return () => {
      for (const q of queries) q.removeEventListener?.('change', update)
    }
  }, [])

  useEffect(() => {
    const el = ref.current
    if (!enabled || !el) return
    const root = document.documentElement
    const target = { x: 0, y: 0 }
    const current = { x: 0, y: 0 }
    let raf = 0
    let started = false

    const apply = () => {
      el.style.transform = `translate3d(${current.x}px, ${current.y}px, 0)`
    }
    const tick = () => {
      const dx = target.x - current.x
      const dy = target.y - current.y
      if (Math.abs(dx) < EPSILON && Math.abs(dy) < EPSILON) {
        current.x = target.x
        current.y = target.y
        apply()
        raf = 0
        return
      }
      current.x += dx * EASE
      current.y += dy * EASE
      apply()
      raf = requestAnimationFrame(tick)
    }

    const onMove = (e: PointerEvent) => {
      if (e.pointerType === 'touch') return
      target.x = e.clientX
      target.y = e.clientY
      if (!started) {
        // Первое появление — сразу под указателем, без «прилёта» из угла.
        started = true
        current.x = target.x
        current.y = target.y
        apply()
        root.classList.add('custom-cursor')
        setMoved(true)
      }
      setInside(true)
      if (!raf) raf = requestAnimationFrame(tick)
    }
    const onOver = (e: PointerEvent) => {
      const next = hoverStateOf(e.target)
      setHover(next.state)
      setOverField(next.field)
      setInside(true)
    }
    const onOut = (e: PointerEvent) => {
      // relatedTarget === null — указатель ушёл за пределы документа (окно, iframe).
      if (e.relatedTarget === null) setInside(false)
    }
    const onDown = (e: PointerEvent) => {
      if (e.pointerType === 'touch') return
      setPressed(true)
      // Нажатие — «прилипаем» к указателю, чтобы видимая рука совпадала с точкой клика.
      target.x = current.x = e.clientX
      target.y = current.y = e.clientY
      apply()
      if (raf) {
        cancelAnimationFrame(raf)
        raf = 0
      }
    }
    const onUp = () => setPressed(false)

    document.addEventListener('pointermove', onMove, { passive: true })
    document.addEventListener('pointerover', onOver, { passive: true })
    document.addEventListener('pointerout', onOut, { passive: true })
    document.addEventListener('pointerdown', onDown, { passive: true })
    document.addEventListener('pointerup', onUp, { passive: true })
    document.addEventListener('pointercancel', onUp, { passive: true })
    return () => {
      document.removeEventListener('pointermove', onMove)
      document.removeEventListener('pointerover', onOver)
      document.removeEventListener('pointerout', onOut)
      document.removeEventListener('pointerdown', onDown)
      document.removeEventListener('pointerup', onUp)
      document.removeEventListener('pointercancel', onUp)
      if (raf) cancelAnimationFrame(raf)
      root.classList.remove('custom-cursor')
    }
  }, [enabled])

  const state: CursorState = pressed ? 'press' : hover
  const visible = moved && inside && !overField
  return { enabled, ref, state, visible }
}
