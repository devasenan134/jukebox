import { QueryClient } from '@tanstack/react-query'
import { catalog } from '../api/catalog'

/**
 * Everything the website has loaded, kept while you move around (like Cauldron's website): going back to a
 * page shows it at once, and it's quietly checked again once it's older than staleTime. The catalog only
 * changes when the library is scanned, so five minutes is plenty.
 */
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: { staleTime: 5 * 60_000, gcTime: 30 * 60_000, retry: 1, refetchOnWindowFocus: false },
  },
})

/** Query keys, in one place so a change can refresh what it touched. */
export const keys = {
  album: (id: string) => ['album', id],
  playlist: (id: string) => ['playlist', id],
  playlists: ['playlists'],
}

/** Starts loading an album before it's opened (on hover or touch), so the page is usually ready on click. */
export const prefetchAlbum = (id: string) => void queryClient.prefetchQuery({ queryKey: keys.album(id), queryFn: () => catalog.album(id) })

/** After changing a playlist: its page and the lists of playlists load fresh. */
export function playlistChanged(id?: string) {
  if (id) void queryClient.invalidateQueries({ queryKey: keys.playlist(id) })
  void queryClient.invalidateQueries({ queryKey: keys.playlists })
}
