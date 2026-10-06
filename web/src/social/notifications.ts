import { create } from 'zustand'
import type { SocialEvent } from '../api/socialTypes'
import { summary } from '../api/socialTypes'
import { load, save } from '../state/storage'
import { me, onEvent, openConversationId, useSocial } from './social'

/**
 * Notifications while Jukebox is in the background (push/Notifications.kt in the app): new messages, friend
 * requests, and a friend starting to listen together in one of your chats. They come while the website (or the
 * Mac app) is open; clicking one brings it forward at the chat. Off until you switch them on in Settings, which
 * asks the browser for permission.
 */

const supported = () => typeof Notification !== 'undefined'

export const useNotifications = create<{ on: boolean; permission: NotificationPermission | 'unsupported'; setOn: (on: boolean) => Promise<void> }>((set) => ({
  on: load('notify.on', false),
  permission: supported() ? Notification.permission : 'unsupported',
  setOn: async (on) => {
    if (on && supported() && Notification.permission !== 'granted') {
      const p = await Notification.requestPermission()
      set({ permission: p })
      if (p !== 'granted') return
    }
    save('notify.on', on)
    set({ on })
  },
}))

/** Whether you'd see it anyway: the page is in front and focused. */
const watching = () => document.visibilityState === 'visible' && document.hasFocus()

/** Opens a page of the website without reloading it (the router listens for this). */
function open(path: string) {
  window.focus()
  if (location.pathname !== path) {
    history.pushState({}, '', path)
    dispatchEvent(new PopStateEvent('popstate'))
  }
}

function show(title: string, body: string, tag: string, path: string) {
  if (!useNotifications.getState().on || !supported() || Notification.permission !== 'granted') return
  try {
    const n = new Notification(title, { body, tag, icon: '/favicon.svg' })
    n.onclick = () => {
      open(path)
      n.close()
    }
  } catch {
    // Some browsers only allow notifications from a service worker; then there are none.
  }
}

/** Chats where a jam was already on, so only a new one is announced. */
const jamming = new Set<number>()

function handle(e: SocialEvent) {
  const myId = me()?.id
  switch (e.type) {
    case 'message': {
      const m = e.message
      if (m.sender.id === myId || m.system || (m.conversationId === openConversationId && watching())) return
      if (watching()) return
      const c = useSocial.getState().conversations.find((x) => x.id === m.conversationId)
      const group = c?.kind === 'group'
      const mentioned = (m.mentions ?? []).includes(myId ?? -1)
      show(
        group ? `${m.sender.displayName} in ${c?.name || 'a group'}` : m.sender.displayName,
        (mentioned ? 'Mentioned you: ' : '') + summary(m),
        `chat-${m.conversationId}`,
        `/chat/${m.conversationId}`,
      )
      break
    }
    case 'friendRequest':
      if (!watching()) show('Friend request', `${e.from.displayName} wants to be friends`, 'friends', '/friends')
      break
    case 'listenSession': {
      const on = e.listeners.length > 0
      const started = on && !jamming.has(e.conversationId)
      if (on) jamming.add(e.conversationId)
      else jamming.delete(e.conversationId)
      if (!started || e.owner === myId || e.listeners.includes(myId ?? -1) || watching()) return
      const c = useSocial.getState().conversations.find((x) => x.id === e.conversationId)
      const who = c?.members.find((u) => u.id === e.owner)?.displayName ?? 'A friend'
      show(`${who} started listening together`, 'Click to join', `jam-${e.conversationId}`, `/chat/${e.conversationId}`)
      break
    }
  }
}

let started = false
export function startNotifications() {
  if (started) return
  started = true
  // Jams already on when the chats load aren't news.
  useSocial.subscribe((s, prev) => {
    if (s.conversations !== prev.conversations) s.conversations.forEach((c) => (c.listeners?.length ? jamming.add(c.id) : null))
  })
  onEvent(handle)
}
