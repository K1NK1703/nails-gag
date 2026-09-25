import { useEffect } from 'react'
import { LegalDocument } from '../components/LegalDocument/LegalDocument'
import { privacy } from '../content/privacy'
import { titleFor } from '../lib/seo'

export function Privacy() {
  useEffect(() => {
    document.title = titleFor('/privacy')
  }, [])
  return <LegalDocument {...privacy} />
}
