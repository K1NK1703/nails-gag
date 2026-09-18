import { Route, Routes } from 'react-router'
import { Footer } from './components/Footer/Footer'
import { Header } from './components/Header/Header'
import { isCabinetEnabled } from './lib/features'
import { Cabinet } from './pages/Cabinet'
import { Home } from './pages/Home'
import { NotFound } from './pages/NotFound'
import { Privacy } from './pages/Privacy'

export function AppRoutes() {
  return (
    <>
      <Header />
      <Routes>
        <Route path="/" element={<Home />} />
        <Route path="/privacy" element={<Privacy />} />
        {isCabinetEnabled() && <Route path="/cabinet" element={<Cabinet />} />}
        <Route path="*" element={<NotFound />} />
      </Routes>
      <Footer />
    </>
  )
}
