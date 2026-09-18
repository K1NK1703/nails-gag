import { mkdir, readdir } from 'node:fs/promises'
import path from 'node:path'
import sharp from 'sharp'

const ROOT = path.resolve(import.meta.dirname, '..')
const SRC = path.resolve(ROOT, '..', 'media', 'photos')
const OUT = path.resolve(ROOT, 'public')
const GALLERY_OUT = path.join(OUT, 'gallery')
const SIZES = [480, 960, 1600]

/** Порядок в галерее. Ключ: имя файла в media/photos без расширения. */
const WORKS: Array<[file: string, slug: string]> = [
  ['IMG_4241', 'work-01'],
  ['IMG_4401', 'work-02'],
  ['IMG_4398', 'work-03'],
  ['IMG_4403', 'work-04'],
  ['IMG_4978', 'work-05'],
  ['IMG_4404', 'work-06'],
  ['IMG_4406', 'work-07'],
  ['IMG_4977', 'work-08'],
  ['IMG_4997', 'work-09'],
  ['IMG_5084', 'work-10'],
  ['IMG_5163', 'work-11'],
]

async function findSource(base: string): Promise<string> {
  const files = await readdir(SRC)
  const hit = files.find((f) => f.toLowerCase().startsWith(base.toLowerCase() + '.'))
  if (!hit) throw new Error(`No source for ${base} in ${SRC}`)
  return path.join(SRC, hit)
}

async function webpSet(input: string, outDir: string, slug: string, sizes: number[]) {
  for (const w of sizes) {
    await sharp(input)
      .rotate()
      .resize({ width: w, withoutEnlargement: true })
      .webp({ quality: 80 })
      .toFile(path.join(outDir, `${slug}-${w}.webp`))
  }
}

async function main() {
  await mkdir(GALLERY_OUT, { recursive: true })

  for (const [file, slug] of WORKS) {
    await webpSet(await findSource(file), GALLERY_OUT, slug, SIZES)
    console.log('gallery', slug)
  }

  await webpSet(await findSource('studio'), GALLERY_OUT, 'studio', SIZES)
  console.log('gallery studio')

  const hero = await findSource('hero-cutout')
  for (const w of [720, 1440]) {
    await sharp(hero)
      .trim()
      .resize({ width: w, withoutEnlargement: true })
      .webp({ quality: 85, alphaQuality: 90 })
      .toFile(path.join(OUT, `hero-${w}.webp`))
  }
  console.log('hero')

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
