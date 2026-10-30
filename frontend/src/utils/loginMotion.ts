/** 将视线限制在眼眶内，同时让远近指针的位置变化保持自然。 */
export function getLoginLookOffset(
  pointer: { x: number; y: number },
  origin: { x: number; y: number },
  radius: number,
) {
  const dx = pointer.x - origin.x
  const dy = pointer.y - origin.y
  const distance = Math.max(180, Math.hypot(dx, dy))
  const limit = Math.max(0, radius)
  return { x: dx / distance * limit, y: dy / distance * limit }
}
