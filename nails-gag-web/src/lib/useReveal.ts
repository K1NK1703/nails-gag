import { useEffect } from 'react'

/** Добавляет .is-visible элементам .reveal, когда они попадают во viewport. */
export function useReveal() {
  useEffect(() => {
    const elements = Array.from(document.querySelectorAll<HTMLElement>('.reveal'))
    if (elements.length === 0) return

    // Сначала помечаем видимое, и только потом включаем класс js: до этого момента
    // CSS не прячет .reveal, поэтому пререндер не мигает скрытым контентом.
    const pending: HTMLElement[] = []
    for (const el of elements) {
      if (el.getBoundingClientRect().top < window.innerHeight) {
        el.classList.add('is-visible')
      } else {
        pending.push(el)
      }
    }
    document.documentElement.classList.add('js')

    if (typeof IntersectionObserver === 'undefined') {
      pending.forEach((el) => el.classList.add('is-visible'))
      return
    }
    const observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (entry.isIntersecting) {
            entry.target.classList.add('is-visible')
            observer.unobserve(entry.target)
          }
        }
      },
      { rootMargin: '0px 0px -10% 0px' },
    )
    pending.forEach((el) => observer.observe(el))
    return () => observer.disconnect()
  }, [])
}
