import { act, fireEvent, render, screen, within } from '@testing-library/react'
import { useState } from 'react'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { CURSOR_KEY, PREFS_EVENT, THEME_KEY } from '../../lib/prefs'
import { Header } from '../Header/Header'
import { Appearance } from './Appearance'

vi.mock('../../lib/analytics', () => ({ track: vi.fn() }))

function stubMatchMedia({ fine = true, dark = false } = {}) {
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: query.includes('pointer') ? fine : query.includes('dark') ? dark : false,
    media: query,
    addEventListener: () => {},
    removeEventListener: () => {},
  }))
}

function Harness() {
  const [open, setOpen] = useState(false)
  return (
    <>
      <Appearance open={open} onOpenChange={setOpen} />
      <button type="button">снаружи</button>
    </>
  )
}

function openPopover() {
  const button = screen.getByRole('button', { name: 'Оформление' })
  fireEvent.click(button)
  return { button, dialog: screen.getByRole('dialog', { name: 'Оформление' }) }
}

afterEach(() => {
  localStorage.clear()
  document.documentElement.removeAttribute('data-theme')
  vi.unstubAllGlobals()
})

describe('Appearance', () => {
  it('кнопка открывает и закрывает поповер', () => {
    stubMatchMedia()
    render(<Harness />)
    const button = screen.getByRole('button', { name: 'Оформление' })
    expect(button).toHaveAttribute('aria-haspopup', 'dialog')
    expect(button).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    const { dialog } = openPopover()
    expect(button).toHaveAttribute('aria-expanded', 'true')
    expect(button).toHaveAttribute('aria-controls', dialog.id)
    fireEvent.click(button)
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('фокус при открытии — на выбранной теме, Escape закрывает и возвращает фокус на кнопку', () => {
    stubMatchMedia()
    render(<Harness />)
    const { button } = openPopover()
    expect(screen.getByRole('radio', { name: 'Как в системе' })).toHaveFocus()
    fireEvent.keyDown(window, { key: 'Escape' })
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(button).toHaveFocus()
  })

  it('закрывается по клику снаружи, но не внутри', () => {
    stubMatchMedia()
    render(<Harness />)
    const { dialog } = openPopover()
    fireEvent.pointerDown(dialog)
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    fireEvent.pointerDown(document.body)
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('закрывается, когда фокус уходит за пределы поповера', () => {
    stubMatchMedia()
    render(<Harness />)
    openPopover()
    const outside = screen.getByRole('button', { name: 'снаружи' })
    fireEvent.blur(screen.getByRole('radio', { name: 'Как в системе' }), { relatedTarget: outside })
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('radio переключает тему: data-theme и localStorage', () => {
    stubMatchMedia()
    render(<Harness />)
    const { dialog } = openPopover()
    const group = within(dialog).getByRole('group', { name: 'Тема' })
    const radios = within(group).getAllByRole('radio')
    expect(radios.map((r) => r.getAttribute('value'))).toEqual(['system', 'light', 'dark'])
    expect(within(group).getByRole('radio', { name: 'Как в системе' })).toBeChecked()

    fireEvent.click(within(group).getByRole('radio', { name: 'Тёмная' }))
    expect(within(group).getByRole('radio', { name: 'Тёмная' })).toBeChecked()
    expect(document.documentElement).toHaveAttribute('data-theme', 'dark')
    expect(localStorage.getItem(THEME_KEY)).toBe('dark')

    fireEvent.click(within(group).getByRole('radio', { name: 'Светлая' }))
    expect(document.documentElement).toHaveAttribute('data-theme', 'light')
    expect(localStorage.getItem(THEME_KEY)).toBe('light')

    fireEvent.click(within(group).getByRole('radio', { name: 'Как в системе' }))
    expect(document.documentElement).not.toHaveAttribute('data-theme')
    expect(localStorage.getItem(THEME_KEY)).toBeNull()
  })

  it('переключатель курсора меняет настройку и шлёт PREFS_EVENT', () => {
    stubMatchMedia({ fine: true })
    render(<Harness />)
    openPopover()
    const toggle = screen.getByRole('switch', { name: 'Фирменный курсор' })
    expect(toggle).toHaveAttribute('aria-checked', 'true')
    const listener = vi.fn()
    window.addEventListener(PREFS_EVENT, listener)
    fireEvent.click(toggle)
    expect(toggle).toHaveAttribute('aria-checked', 'false')
    expect(localStorage.getItem(CURSOR_KEY)).toBe('off')
    expect(listener).toHaveBeenCalledTimes(1)
    fireEvent.click(toggle)
    expect(toggle).toHaveAttribute('aria-checked', 'true')
    expect(localStorage.getItem(CURSOR_KEY)).toBe('on')
    window.removeEventListener(PREFS_EVENT, listener)
  })

  it('на тач-устройствах переключателя курсора нет', () => {
    stubMatchMedia({ fine: false })
    render(<Harness />)
    openPopover()
    expect(screen.queryByRole('switch')).not.toBeInTheDocument()
  })

  it('без matchMedia (как при SSR) рендерит только выбор темы', () => {
    vi.stubGlobal('matchMedia', undefined)
    render(<Harness />)
    openPopover()
    expect(screen.getAllByRole('radio')).toHaveLength(3)
    expect(screen.queryByRole('switch')).not.toBeInTheDocument()
  })

  it('подхватывает сохранённый выбор', () => {
    stubMatchMedia()
    localStorage.setItem(THEME_KEY, 'light')
    render(<Harness />)
    openPopover()
    expect(screen.getByRole('radio', { name: 'Светлая' })).toBeChecked()
    expect(document.documentElement).toHaveAttribute('data-theme', 'light')
  })

  it('в шапке: открытие «Оформления» закрывает меню и наоборот', () => {
    stubMatchMedia()
    render(
      <MemoryRouter>
        <Header />
      </MemoryRouter>,
    )
    const burger = screen.getByRole('button', { name: /меню/i })
    fireEvent.click(burger)
    expect(burger).toHaveAttribute('aria-expanded', 'true')
    const { button } = openPopover()
    expect(burger).toHaveAttribute('aria-expanded', 'false')
    act(() => fireEvent.click(burger))
    expect(button).toHaveAttribute('aria-expanded', 'false')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })
})
