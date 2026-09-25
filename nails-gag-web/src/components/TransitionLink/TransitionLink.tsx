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

/** Номер последнего запущенного перехода: метку снимает только он, а не вытесненный им. */
let transitionToken = 0

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
      // Шапке имя перехода даём только на время анимации, см. Header.module.css.
      const root = document.documentElement
      const token = ++transitionToken
      root.dataset['navTransition'] = ''
      const transition = document.startViewTransition(() => {
        flushSync(go)
      })
      const done = () => {
        if (token === transitionToken) delete root.dataset['navTransition']
      }
      void Promise.resolve(transition.finished).then(done, done)
    } else {
      go()
    }
  }

  return <Link to={to} replace={replace} state={state} onClick={handleClick} {...rest} />
}
