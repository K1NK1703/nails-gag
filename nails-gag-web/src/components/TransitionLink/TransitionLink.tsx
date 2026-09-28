import type { MouseEvent } from 'react'
import { flushSync } from 'react-dom'
import { Link, useNavigate } from 'react-router'
import type { LinkProps } from 'react-router'

export type TransitionLinkProps = Omit<LinkProps, 'to' | 'viewTransition'> & { to: string }

function isPlainClick(event: MouseEvent<HTMLAnchorElement>, target: string | undefined): boolean {
  return (
    event.button === 0 &&
    (!target || target === '_self') &&
    !event.metaKey &&
    !event.ctrlKey &&
    !event.shiftKey &&
    !event.altKey
  )
}

function prefersReducedMotion(): boolean {
  return (
    typeof window.matchMedia === 'function' &&
    window.matchMedia('(prefers-reduced-motion: reduce)').matches
  )
}

/**
 * Link с переходом через View Transitions API.
 * В декларативном BrowserRouter проп viewTransition у Link ничего не делает
 * (startViewTransition вызывает только RouterProvider), поэтому оборачиваем
 * навигацию сами: flushSync нужен, чтобы новый DOM был готов к снимку «после».
 */
export function TransitionLink({ to, onClick, replace, state, ...rest }: TransitionLinkProps) {
  const navigate = useNavigate()

  const handleClick = (event: MouseEvent<HTMLAnchorElement>) => {
    onClick?.(event)
    if (event.defaultPrevented || rest.reloadDocument || !isPlainClick(event, rest.target)) return
    event.preventDefault()

    const go = () => {
      void navigate(to, { replace, state })
    }
    if (typeof document.startViewTransition === 'function' && !prefersReducedMotion()) {
      // Никаким элементам со стеклом view-transition-name не даём: в WebKit такой элемент
      // становится backdrop root, и после перехода размытие шапки и меню пропадало.
      document.startViewTransition(() => {
        flushSync(go)
      })
    } else {
      go()
    }
  }

  return <Link to={to} replace={replace} state={state} onClick={handleClick} {...rest} />
}
