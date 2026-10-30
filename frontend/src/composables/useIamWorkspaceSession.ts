import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useAuthStore } from '../stores/auth'
import { useIamS1Store } from '../stores/iamS1'
/** UI request ownership only. Does not join roles, grant functions or depend on public metadata scope. */
export function useIamWorkspaceSession() {
  const auth = useAuthStore(),
    iam = useIamS1Store(),
    generation = ref(0)
  let disposed = false
  const ready = computed(() => iam.loaded && !iam.errorMessage)
  const key = computed(() => JSON.stringify([auth.token, iam.snapshot, iam.loaded, iam.errorMessage]))
  const cancelClass = `iam-confirm-${Math.random().toString(36).slice(2)}`
  function cancelOwn() {
    document.querySelector<HTMLButtonElement>(`.${cancelClass}`)?.click()
  }
  function capture() {
    return generation.value
  }
  function current(g: number) {
    return !disposed && g === generation.value && ready.value
  }
  function can(code: string, source?: number) {
    return !disposed && ready.value && !!source && iam.canOnDatasource(code, source)
  }
  function canGlobal(code: string) {
    return !disposed && ready.value && iam.hasGlobal(code)
  }
  watch(
    key,
    () => {
      generation.value++
      cancelOwn()
    },
    { flush: 'sync' },
  )
  onBeforeUnmount(() => {
    disposed = true
    generation.value++
    cancelOwn()
  })
  return {
    auth,
    iam,
    ready,
    key,
    generation,
    capture,
    current,
    can,
    canGlobal,
    cancelClass,
    cancelOwn,
    alive: () => !disposed,
  }
}
