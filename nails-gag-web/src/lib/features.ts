export function isCabinetEnabled(): boolean {
  return import.meta.env.VITE_FEATURE_CABINET === 'true'
}
