// 演示环境共用的常量：端口、目录、容器名。
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const REPO = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
export const STATE = path.join(REPO, '.runtime', 'demo');
export const PORTS = { backend: 19420, postgres: 19421, redis: 19422, minio: 19423, minioConsole: 19424, admin: 19430, console: 19440, h5: 19450, wechat: 19460 };
export const PROJECT = 'low-altitude-demo';
export const PG_CONTAINER = `${PROJECT}-postgres-1`;

export function readEnv(target) {
  return Object.fromEntries(fs.readFileSync(target, 'utf8').split('\n').filter((line) => /^[A-Z0-9_]+=/.test(line))
    .map((line) => [line.slice(0, line.indexOf('=')), line.slice(line.indexOf('=') + 1)]));
}
