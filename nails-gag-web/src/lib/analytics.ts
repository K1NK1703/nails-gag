export type CtaPlace = 'header' | 'hero' | 'price'

export type ContactChannel = 'telegram'

export type AnalyticsEvent =
  | { name: 'page_view'; path: string }
  | { name: 'cta_click'; place: CtaPlace }
  | { name: 'contact_click'; channel: ContactChannel }
  | { name: 'gallery_open'; index: number }
  | { name: 'faq_open'; id: string }
  | { name: 'map_open' }

export function track(event: AnalyticsEvent): void {
  const endpoint = import.meta.env.VITE_ANALYTICS_ENDPOINT
  if (!endpoint || typeof navigator === 'undefined' || !navigator.sendBeacon) return
  const payload = { ...event, ts: Date.now() }
  const blob = new Blob([JSON.stringify(payload)], { type: 'application/json' })
  navigator.sendBeacon(endpoint, blob)
}
