import { useEffect, useRef } from 'react'
import { useLocation, useNavigationType } from 'react-router'

/**
 * Прокручивает к началу страницы при переходе на новый маршрут (PUSH/REPLACE),
 * чтобы переход между страницами (см. View Transitions в global.css) не оставлял
 * пользователя посреди прошлой страницы.
 * Не трогает прокрутку:
 * - при первом монтировании — браузер сам восстанавливает позицию или ведёт к якорю;
 * - на POP (назад/вперёд) — позицию восстанавливает браузер;
 * - если в адресе есть хэш — браузер ведёт к нужному якорю (например, /#contacts).
 */
export function useScrollToTop() {
  const location = useLocation()
  const navigationType = useNavigationType()
  const mounted = useRef(false)

  useEffect(() => {
    if (!mounted.current) {
      mounted.current = true
      return
    }
    if (navigationType === 'POP' || location.hash) return
    window.scrollTo({ top: 0, left: 0, behavior: 'instant' })
  }, [location, navigationType])
}
