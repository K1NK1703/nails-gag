import { About } from '../components/About/About'
import { Contacts } from '../components/Contacts/Contacts'
import { Faq } from '../components/Faq/Faq'
import { Gallery } from '../components/Gallery/Gallery'
import { Hero } from '../components/Hero/Hero'
import { Price } from '../components/Price/Price'
import { Reviews } from '../components/Reviews/Reviews'
import { useReveal } from '../lib/useReveal'

export function Home() {
  useReveal()
  return (
    <main>
      <Hero />
      <Gallery />
      <Price />
      <About />
      <Reviews />
      <Faq />
      <Contacts />
    </main>
  )
}
