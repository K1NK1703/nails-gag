export type Photo = {
  slug: string
  alt: string
  caption?: string
}

export const GALLERY_SIZES = [480, 960, 1600] as const

export const gallery: Photo[] = [
  { slug: 'work-01', alt: 'Молочный квадрат с глянцевым топом', caption: 'Молочный квадрат' },
  { slug: 'work-02', alt: 'Нюдовый квадрат с цветами на двух ногтях', caption: 'Флоры' },
  { slug: 'work-03', alt: 'Длинный квадрат с белым френчем на бежевой основе' },
  { slug: 'work-04', alt: 'Молочный квадрат с золотой поталью и тонким рисунком' },
  { slug: 'work-05', alt: 'Нюдовый квадрат с серебристым шиммером', caption: 'Шиммер' },
  { slug: 'work-06', alt: 'Классический белый френч на розовой основе' },
  { slug: 'work-07', alt: 'Короткий квадрат с нежно-розовым френчем' },
  { slug: 'work-08', alt: 'Нюдовый квадрат с плотным переливающимся глиттером' },
  { slug: 'work-09', alt: 'Тёмно-графитовый квадрат с глянцевым топом' },
  { slug: 'work-10', alt: 'Розовый глиттер с белым френчем на длинном квадрате' },
  { slug: 'work-11', alt: 'Нюдовый квадрат с тонкими графичными веточками' },
]

export function srcSet(slug: string): string {
  return GALLERY_SIZES.map((w) => `/gallery/${slug}-${w}.webp ${w}w`).join(', ')
}

export function src(slug: string, width: (typeof GALLERY_SIZES)[number] = 960): string {
  return `/gallery/${slug}-${width}.webp`
}
