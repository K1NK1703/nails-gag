export type Price = {
  id: string
  category: string
  title: string
  price: string
  description: string
  primary?: boolean
}

export const prices: Price[] = [
  {
    id: 'french',
    category: 'Доп. покрытие',
    title: 'Френч',
    price: '+ 300 ₽',
    description: 'Классический или цветной — на ваш выбор',
  },
  {
    id: 'manicure',
    category: 'Основная услуга',
    title: 'Маникюр',
    price: '2 500 ₽',
    description: 'Любой исходник · аппаратная техника · покрытие гель-лаком',
    primary: true,
  },
  {
    id: 'design',
    category: 'Дизайн',
    title: 'Втирка / дизайн',
    price: '+ 200 ₽',
    description: 'На все ногти — мягкие переливы, флоры, акценты',
  },
]
