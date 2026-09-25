/** Раздел юридического документа (политика, соглашение). */
export type LegalSection = {
  /** Якорь раздела: на странице становится id="p-<id>". */
  id: string
  title: string
  paragraphs: string[]
  list?: string[]
}
