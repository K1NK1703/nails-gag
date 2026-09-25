import { useEffect } from 'react'
import { useLocation } from 'react-router'

/**
 * Прокручивает к началу страницы при смене маршрута, чтобы переход между
 * страницами (см. View Transitions в global.css) не оставлял пользователя
 * посреди прошлой страницы. Пропускает прокрутку, если в адресе есть хэш —
 * тогда браузер сам ведёт к нужному якорю (например, /#contacts).
 */
export function useScrollToTop() {
  const location = useLocation()

  useEffect(() => {
    if (location.hash) return
    window.scrollTo({ top: 0, left: 0, behavior: 'instant' })
  }, [location])
}
