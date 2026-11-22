import { describe, expect, it } from 'vitest';
import { sign, signV2, verify } from '../src/signature';

const SECRET = 'segredo-do-lojista-64-hex-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx';

describe('signature (Spec 35 + Spec 148/150)', () => {
  const body = JSON.stringify({ eventId: 'abc', type: 'SALE_PAID' });
  const NOW_MS = 1_720_000_000_000; // fixed para testes de janela

  describe('v1 (legado — hex puro do HMAC do body)', () => {
    it('sign produz HMAC-SHA256 hex do raw body', () => {
      const s = sign(body, SECRET);
      expect(s).toMatch(/^[0-9a-f]{64}$/);
      // deterministic
      expect(sign(body, SECRET)).toBe(s);
    });

    it('verify aceita assinatura correta (tempo constante)', () => {
      expect(verify(body, SECRET, sign(body, SECRET))).toBe(true);
    });

    it('verify rejeita assinatura de outro segredo', () => {
      expect(verify(body, SECRET, sign(body, 'outro-segredo'))).toBe(false);
    });

    it('verify rejeita assinatura de outro body', () => {
      const other = JSON.stringify({ eventId: 'abc', type: 'SALE_REFUNDED' });
      expect(verify(body, SECRET, sign(other, SECRET))).toBe(false);
    });

    it('verify rejeita assinatura ausente ou vazia', () => {
      expect(verify(body, SECRET, undefined)).toBe(false);
      expect(verify(body, SECRET, '')).toBe(false);
    });

    it('verify rejeita body vazio', () => {
      expect(verify('', SECRET, sign('', SECRET))).toBe(false);
    });

    it('verify rejeita assinatura com comprimento diferente (sem timingSafeEqual)', () => {
      expect(verify(body, SECRET, 'abc')).toBe(false);
      expect(verify(body, SECRET, `${sign(body, SECRET)}ff`)).toBe(false);
    });
  });

  describe('v2 (Spec 148 — t=,v1= com HMAC de "<t>.<body>")', () => {
    it('signV2 produz t=<unix>,v1=<64 hex>', () => {
      const s = signV2(body, SECRET, 1_720_000_000);
      expect(s).toMatch(/^t=1720000000,v1=[0-9a-f]{64}$/);
    });

    it('verify aceita v2 válido dentro da janela', () => {
      const s = signV2(body, SECRET, 1_720_000_000);
      expect(verify(body, SECRET, s, { nowMs: NOW_MS })).toBe(true);
    });

    it('verify rejeita v2 com body-tamper (HMAC de <t>.<body>)', () => {
      const s = signV2(body, SECRET, 1_720_000_000);
      const tampered = JSON.stringify({ eventId: 'abc', type: 'SALE_REFUNDED' });
      expect(verify(tampered, SECRET, s, { nowMs: NOW_MS })).toBe(false);
    });

    it('verify rejeita v2 com t fora da janela (replay velho)', () => {
      const s = signV2(body, SECRET, 1_720_000_000 - 400); // 400s atrás (> 5 min)
      expect(verify(body, SECRET, s, { nowMs: NOW_MS, maxAgeMs: 5 * 60 * 1000 })).toBe(false);
    });

    it('verify aceita v2 no limite da janela', () => {
      const s = signV2(body, SECRET, 1_720_000_000 - 299); // 299s < 5 min
      expect(verify(body, SECRET, s, { nowMs: NOW_MS })).toBe(true);
    });

    it('verify rejeita v2 com t no futuro (relógio)', () => {
      const s = signV2(body, SECRET, 1_720_000_000 + 120); // 2 min no futuro
      expect(verify(body, SECRET, s, { nowMs: NOW_MS })).toBe(false);
    });

    it('verify rejeita v2 com outro segredo', () => {
      const s = signV2(body, 'outro-segredo', 1_720_000_000);
      expect(verify(body, SECRET, s, { nowMs: NOW_MS })).toBe(false);
    });
  });
});
