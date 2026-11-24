/**
 * 图片在页面上用的地址。现场演示的服务器给的是一个不存在的 HTTPS 主机（media.demo.localhost，docs/ops/现场演示.md），
 * 显示时换成本站同源的路径，由 /api 代理回后端；其他地址原样。只用于显示，提交给后端的仍是原地址。
 */
export function displayMediaUrl(url: string | null | undefined): string {
  const demo = 'https://media.demo.localhost/'
  return url?.startsWith(demo) ? `/${url.slice(demo.length)}` : url ?? ''
}
