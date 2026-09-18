export type Photo = {
  slug: string
  alt: string
  caption?: string
}

export const GALLERY_SIZES = [480, 960, 1600] as const

export const gallery: Photo[] = [
  { slug: 'work-01', alt: 'Молочный квадрат с глянцевым покрытием', caption: 'Молочный квадрат' },
  { slug: 'work-02', alt: 'Нюдовый маникюр с цветочным дизайном на двух ногтях', caption: 'Флоры' },
  { slug: 'work-03', alt: 'Короткий квадрат в тёплом бежевом оттенке' },
  { slug: 'work-04', alt: 'Розовый френч с мерцанием' },
  { slug: 'work-05', alt: 'Прозрачное покрытие с серебристой втиркой', caption: 'Pink shimmer' },
  { slug: 'work-06', alt: 'Классический белый френч на квадрате' },
  { slug: 'work-07', alt: 'Нюд с блёстками и мелким декором' },
  { slug: 'work-08', alt: 'Нежно-розовый квадрат средней длины' },
  { slug: 'work-09', alt: 'Тёмное глянцевое покрытие' },
  { slug: 'work-10', alt: 'Светлый нюд с тонким френчем' },
  { slug: 'work-11', alt: 'Бежевый квадрат с матовым топом' },
]

export function srcSet(slug: string): string {
  return GALLERY_SIZES.map((w) => `/gallery/${slug}-${w}.webp ${w}w`).join(', ')
}

export function src(slug: string, width: (typeof GALLERY_SIZES)[number] = 960): string {
  return `/gallery/${slug}-${width}.webp`
}
