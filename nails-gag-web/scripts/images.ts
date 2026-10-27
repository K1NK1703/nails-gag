import { mkdir, readdir } from 'node:fs/promises'
import path from 'node:path'
import sharp from 'sharp'
import { about } from '../src/content/about.ts'

const ROOT = path.resolve(import.meta.dirname, '..')
const SRC = path.resolve(ROOT, '..', 'media', 'photos')
const OUT = path.resolve(ROOT, 'public')
// Работы портфолио сюда не входят: их ведёт мастер, картинки отдаёт бэкенд (GET /api/portfolio).

async function findSource(base: string): Promise<string> {
  const files = await readdir(SRC)
  const hit = files.find((f) => f.toLowerCase().startsWith(base.toLowerCase() + '.'))
  if (!hit) throw new Error(`No source for ${base} in ${SRC}`)
  return path.join(SRC, hit)
}

async function webpSet(input: string, outDir: string, slug: string, sizes: readonly number[]) {
  for (const w of sizes) {
    await sharp(input)
      .rotate()
      .resize({ width: w, withoutEnlargement: true })
      .webp({ quality: 80 })
      .toFile(path.join(outDir, `${slug}-${w}.webp`))
  }
}

async function main() {
  await mkdir(OUT, { recursive: true })

  // Фото мастера в студии для раздела «О мастере»: имя и ширины — те же, что берёт About.
  await webpSet(await findSource('studio'), OUT, about.photo.name, about.photo.widths)
  console.log(about.photo.name)

  const heroSrc = await findSource('hero-cutout')
  const trimmed = await sharp(heroSrc).trim().png().toBuffer({ resolveWithObject: true })
  const { width: tw, height: th } = trimmed.info

  // Desktop: голова → чуть выше колен.
  const kneeUp = await sharp(trimmed.data)
    .extract({ left: 0, top: 0, width: tw, height: Math.round(th * 0.68) })
    .png()
    .toBuffer()
  for (const w of [480, 720, 960]) {
    const info = await sharp(kneeUp)
      .resize({ width: w, withoutEnlargement: true })
      .webp({ quality: 85, alphaQuality: 90 })
      .toFile(path.join(OUT, `hero-${w}.webp`))
    console.log(`hero-${w}`, `${info.width}x${info.height}`)
  }

  // Mobile: портрет (голова и плечи) для карточки мастера.
  // Лицо по центру: берём центральные 70% ширины, чтобы в круге 88px оно было крупнее.
  const pw = Math.round(tw * 0.7)
  const portrait = await sharp(trimmed.data)
    .extract({
      left: Math.round((tw - pw) / 2),
      top: 0,
      width: pw,
      height: Math.min(th, Math.round(pw * 1.15)),
    })
    .png()
    .toBuffer()
  for (const w of [240, 480]) {
    const info = await sharp(portrait)
      .resize({ width: w, withoutEnlargement: true })
      .webp({ quality: 85, alphaQuality: 90 })
      .toFile(path.join(OUT, `portrait-${w}.webp`))
    console.log(`portrait-${w}`, `${info.width}x${info.height}`)
  }

  await sharp(await findSource('IMG_4978'))
    .rotate()
    .resize({ width: 1200, height: 630, fit: 'cover', position: 'attention' })
    .jpeg({ quality: 82 })
    .toFile(path.join(OUT, 'og.jpg'))
  console.log('og')
}

main().catch((e) => {
  console.error(e)
  process.exit(1)
})
