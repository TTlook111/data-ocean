import { onBeforeUnmount, onMounted, watch } from 'vue'

/**
 * 锁住后台内容区滚动。
 *
 * Element Plus 的 lock-scroll 只处理 body，但后台真正的滚动容器是
 * `.admin-shell__content`。弹窗/抽屉打开时如果不锁它，会出现外层页面和
 * 内层表单同时滚动。这里只改该容器，不修改全局 body 或其他页面。
 */
export function useAdminScrollLock(isLocked: () => boolean) {
  let lockedElement: HTMLElement | null = null
  let previousOverflowY = ''
  let previousOverscrollBehavior = ''

  function lock() {
    const element = document.querySelector<HTMLElement>('.admin-shell__content')
    if (!element || lockedElement === element) return
    lockedElement = element
    previousOverflowY = element.style.overflowY
    previousOverscrollBehavior = element.style.overscrollBehavior
    element.style.overflowY = 'hidden'
    element.style.overscrollBehavior = 'contain'
  }

  function unlock() {
    if (!lockedElement) return
    lockedElement.style.overflowY = previousOverflowY
    lockedElement.style.overscrollBehavior = previousOverscrollBehavior
    lockedElement = null
  }

  function sync() {
    if (isLocked()) lock()
    else unlock()
  }

  onMounted(sync)
  watch(isLocked, sync, { flush: 'post' })
  onBeforeUnmount(unlock)
}
