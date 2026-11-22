import { describe, expect, it } from 'vitest';
import { COMMAND_CATEGORY, WEBHOOK_ENV, loadWebhooks } from '../src/webhooks.js';

describe('webhooks', () => {
  it('mapeia as 7 categorias para as env vars', () => {
    expect(Object.keys(WEBHOOK_ENV)).toHaveLength(7);
    expect(WEBHOOK_ENV.cmd).toBe('WEBHOOK_CMD');
    expect(WEBHOOK_ENV.ops).toBe('WEBHOOK_OPS');
    expect(WEBHOOK_ENV.finPulse).toBe('WEBHOOK_FIN_PULSE');
    expect(WEBHOOK_ENV.summaries).toBe('WEBHOOK_SUMMARIES');
    expect(WEBHOOK_ENV.health).toBe('WEBHOOK_HEALTH');
    expect(WEBHOOK_ENV.security).toBe('WEBHOOK_SECURITY');
    expect(WEBHOOK_ENV.vendedores).toBe('WEBHOOK_VENDEDORES');
  });

  it('carrega apenas as categorias preenchidas (trim de espaços)', () => {
    const cfg = loadWebhooks({
      WEBHOOK_SUMMARIES: 'https://discord.com/api/webhooks/1/abc',
      WEBHOOK_FIN_PULSE: '  https://discord.com/api/webhooks/2/def  ',
      WEBHOOK_OPS: '',
      WEBHOOK_HEALTH: undefined,
    } as NodeJS.ProcessEnv);
    expect(cfg.summaries).toBe('https://discord.com/api/webhooks/1/abc');
    expect(cfg.finPulse).toBe('https://discord.com/api/webhooks/2/def');
    expect(cfg.ops).toBeUndefined();
    expect(cfg.health).toBeUndefined();
  });

  it('retorna config vazia sem nenhuma env', () => {
    expect(loadWebhooks({})).toEqual({});
  });

  it('normaliza discordapp.com → discord.com (alias legado)', () => {
    const cfg = loadWebhooks({
      WEBHOOK_FIN_PULSE: 'https://discordapp.com/api/webhooks/1536/ABC_def',
    } as NodeJS.ProcessEnv);
    expect(cfg.finPulse).toBe('https://discord.com/api/webhooks/1536/ABC_def');
  });

  it('mapeia cada comando para a categoria (requirements §6 + canais do usuário)', () => {
    // vendedores = informações dos vendedores (negócio + vendedores por IP)
    for (const cmd of ['vendas', 'chargebacks', 'lojistas', 'receita', 'top5', 'ip', 'vigiar']) {
      expect(COMMAND_CATEGORY[cmd]).toBe('vendedores');
    }
    // finPulse fica só p/ a anomalia (agendado — sem comando interativo)
    // saúde e sistema
    for (const cmd of ['saude', 'conexoes', 'outbox', 'jobs', 'latencia']) {
      expect(COMMAND_CATEGORY[cmd]).toBe('health');
    }
    // segurança e auditoria
    for (const cmd of ['alertas', 'bloqueios', 'reconciliacao']) {
      expect(COMMAND_CATEGORY[cmd]).toBe('security');
    }
    // OPS
    for (const cmd of ['deploy', 'bloquear', 'ratelimit', 'log']) {
      expect(COMMAND_CATEGORY[cmd]).toBe('ops');
    }
  });
});
