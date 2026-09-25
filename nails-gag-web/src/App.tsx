import { useEffect } from 'react'
import { Route, Routes, useLocation } from 'react-router'
import { Cursor } from './components/Cursor/Cursor'
import { Footer } from './components/Footer/Footer'
import { Header } from './components/Header/Header'
import { track } from './lib/analytics'
import { isCabinetEnabled } from './lib/features'
import { useScrollToTop } from './lib/useScrollToTop'
import { Booking } from './pages/Booking'
import { Cabinet } from './pages/Cabinet'
import { Home } from './pages/Home'
import { NotFound } from './pages/NotFound'
import { Privacy } from './pages/Privacy'

export function AppRoutes() {
  const { pathname } = useLocation()

  useScrollToTop()

  useEffect(() => {
    track({ name: 'page_view', path: pathname })
  }, [pathname])

  return (
    <>
      <Header />
      <Routes>
        <Route path="/" element={<Home />} />
        <Route path="/privacy" element={<Privacy />} />
        <Route path="/booking" element={<Booking />} />
        {isCabinetEnabled() && <Route path="/cabinet" element={<Cabinet />} />}
        <Route path="*" element={<NotFound />} />
      </Routes>
      <Footer />
      <Cursor />
    </>
  )
}
