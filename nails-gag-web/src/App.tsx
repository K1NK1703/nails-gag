import { Route, Routes } from 'react-router'
import { Cursor } from './components/Cursor/Cursor'
import { Footer } from './components/Footer/Footer'
import { Header } from './components/Header/Header'
import { isCabinetEnabled } from './lib/features'
import { usePageViews } from './lib/usePageViews'
import { useScrollToTop } from './lib/useScrollToTop'
import { Booking } from './pages/Booking'
import { Cabinet } from './pages/Cabinet'
import { Home } from './pages/Home'
import { NotFound } from './pages/NotFound'
import { Privacy } from './pages/Privacy'
import { Terms } from './pages/Terms'

export function AppRoutes() {
  useScrollToTop()
  usePageViews()

  return (
    <>
      <Header />
      <Routes>
        <Route path="/" element={<Home />} />
        <Route path="/privacy" element={<Privacy />} />
        <Route path="/terms" element={<Terms />} />
        <Route path="/booking" element={<Booking />} />
        {isCabinetEnabled() && <Route path="/cabinet" element={<Cabinet />} />}
        <Route path="*" element={<NotFound />} />
      </Routes>
      <Footer />
      <Cursor />
    </>
  )
}
