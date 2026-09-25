import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'

// jsdom не реализует scrollTo и пишет «Not implemented» в консоль; тесты, которым он
// важен, подменяют его через vi.spyOn.
window.scrollTo = () => {}

afterEach(() => cleanup())
