/**
 * Logger estruturado (JSON, level configurável) — Spec 52: nenhum dado sensível
 * (payload de ações, token, secret, platformUserId) é logado. A sanitarização
 * é de responsabilidade de quem chama (nunca passar valores sensíveis aqui).
 */

export type LogLevel = 'debug' | 'info' | 'warn' | 'error';

const LEVELS: Record<LogLevel, number> = { debug: 10, info: 20, warn: 30, error: 40 };

export interface Logger {
  debug(msg: string, fields?: Record<string, unknown>): void;
  info(msg: string, fields?: Record<string, unknown>): void;
  warn(msg: string, fields?: Record<string, unknown>): void;
  error(msg: string, fields?: Record<string, unknown>): void;
}

export function createLogger(level: LogLevel = 'info'): Logger {
  const threshold = LEVELS[level];
  const write = (lvl: LogLevel, msg: string, fields?: Record<string, unknown>) => {
    if (LEVELS[lvl] < threshold) {
      return;
    }
    const entry = { ts: new Date().toISOString(), level: lvl, msg, ...fields };
    const line = JSON.stringify(entry);
    if (lvl === 'error') {
      // eslint-disable-next-line no-console
      console.error(line);
    } else {
      // eslint-disable-next-line no-console
      console.log(line);
    }
  };
  return {
    debug: (m, f) => write('debug', m, f),
    info: (m, f) => write('info', m, f),
    warn: (m, f) => write('warn', m, f),
    error: (m, f) => write('error', m, f),
  };
}
