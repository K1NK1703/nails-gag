export type User = { name: string; avatarUrl?: string }

/**
 * Заглушка авторизации до появления бэкенда: всегда гость.
 * VITE_PREVIEW_USER — только для просмотра дизайна вошедшего пользователя.
 */
export function useAuth(): { user: User | null } {
  const preview = import.meta.env.VITE_PREVIEW_USER
  return { user: preview ? { name: preview } : null }
}
