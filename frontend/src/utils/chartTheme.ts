/** Canvas 与导出的独立 SVG 需要真实颜色值，统一从当前容器继承的主题令牌读取。 */
export function readChartTheme(element: Element) {
  const style = getComputedStyle(element)
  const color = (token: `--do-${string}`) => style.getPropertyValue(token).trim()
  return { color, fontFamily: style.fontFamily }
}
