import { useEffect, useRef } from 'react'

const MAX_DEG = 5

/**
 * Лёгкий 3D-наклон элемента к курсору: пишет --rx/--ry (градусы) и --mx/--my (проценты)
 * в style элемента. Только на устройствах с hover и без prefers-reduced-motion.
 * Слушатели вешаются в эффекте, поэтому безопасно для пререндера.
 */
export function useTilt<T extends HTMLElement>() {
  const ref = useRef<T>(null)

  useEffect(() => {
    const el = ref.current
    if (!el || typeof window.matchMedia !== 'function') return
    if (!window.matchMedia('(hover: hover)').matches) return
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return

    const onMove = (e: PointerEvent) => {
      const rect = el.getBoundingClientRect()
      const w = rect.width || 1
      const h = rect.height || 1
      // -0.5..0.5 от центра
      const px = Math.min(Math.max((e.clientX - rect.left) / w, 0), 1) - 0.5
      const py = Math.min(Math.max((e.clientY - rect.top) / h, 0), 1) - 0.5
      el.style.setProperty('--ry', `${(px * 2 * MAX_DEG).toFixed(2)}deg`)
      el.style.setProperty('--rx', `${(-py * 2 * MAX_DEG).toFixed(2)}deg`)
      el.style.setProperty('--mx', `${((px + 0.5) * 100).toFixed(1)}%`)
      el.style.setProperty('--my', `${((py + 0.5) * 100).toFixed(1)}%`)
    }
    const onLeave = () => {
      for (const p of ['--rx', '--ry', '--mx', '--my']) el.style.removeProperty(p)
    }

    el.addEventListener('pointermove', onMove)
    el.addEventListener('pointerleave', onLeave)
    return () => {
      el.removeEventListener('pointermove', onMove)
      el.removeEventListener('pointerleave', onLeave)
      onLeave()
    }
  }, [])

  return ref
}
