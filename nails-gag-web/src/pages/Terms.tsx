import { useEffect } from 'react'
import { LegalDocument } from '../components/LegalDocument/LegalDocument'
import { terms } from '../content/terms'
import { titleFor } from '../lib/seo'

export function Terms() {
  useEffect(() => {
    document.title = titleFor('/terms')
  }, [])
  return <LegalDocument {...terms} />
}
