import { useEffect } from 'react'

/**
 * Добавляет .is-visible элементам .reveal, когда они попадают во viewport,
 * и снимает при выходе, чтобы появление проигрывалось при каждом возврате.
 */
export function useReveal() {
  useEffect(() => {
    const elements = Array.from(document.querySelectorAll<HTMLElement>('.reveal'))
    if (elements.length === 0) return

    const root = document.documentElement
    const dropJs = () => root.classList.remove('js')

    // Сначала помечаем видимое, и только потом включаем класс js: до этого момента
    // CSS не прячет .reveal, поэтому пререндер не мигает скрытым контентом.
    for (const el of elements) {
      if (el.getBoundingClientRect().top < window.innerHeight) el.classList.add('is-visible')
    }
    root.classList.add('js')

    if (typeof IntersectionObserver === 'undefined') {
      elements.forEach((el) => el.classList.add('is-visible'))
      return dropJs
    }
    // Два наблюдателя: показываем, когда элемент зашёл на 10% выше нижнего края,
    // а прячем, только когда он целиком ушёл за экран. Так элемент у нижней кромки
    // не гаснет при первом колбэке и не мигает на границе зоны.
    const show = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (entry.isIntersecting) entry.target.classList.add('is-visible')
        }
      },
      { rootMargin: '0px 0px -10% 0px' },
    )
    const hide = new IntersectionObserver((entries) => {
      for (const entry of entries) {
        if (!entry.isIntersecting) entry.target.classList.remove('is-visible')
      }
    })
    for (const el of elements) {
      show.observe(el)
      hide.observe(el)
    }
    // Снимаем js при размонтировании, иначе после возврата на главную
    // .reveal остаются скрытыми до следующего эффекта.
    return () => {
      show.disconnect()
      hide.disconnect()
      dropJs()
    }
  }, [])
}
