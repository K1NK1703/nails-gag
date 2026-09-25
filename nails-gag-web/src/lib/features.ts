/** Кабинет включён по умолчанию; VITE_FEATURE_CABINET=false прячет пункт и маршрут. */
export function isCabinetEnabled(): boolean {
  return import.meta.env.VITE_FEATURE_CABINET !== 'false'
}
