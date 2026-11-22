import { describe, expect, it } from 'vitest';
import {
  chargebackVerdict,
  jobsVerdict,
  latencyVerdict,
  outboxVerdict,
  reconciliationVerdict,
  salesVerdict,
  usageVerdict,
} from '../src/format.js';

describe('latencyVerdict', () => {
  it('classifica p95 em ótimo/bom/regular/ruim', () => {
    expect(latencyVerdict(0).label).toContain('sem amostras');
    expect(latencyVerdict(150).label).toContain('ótimo');
    expect(latencyVerdict(400).label).toContain('bom');
    expect(latencyVerdict(700).label).toContain('regular');
    expect(latencyVerdict(2000).label).toContain('ruim');
  });

  it('dá conselho apenas nos níveis de atenção', () => {
    expect(latencyVerdict(700).advice).toBeTruthy();
    expect(latencyVerdict(2000).advice).toBeTruthy();
    expect(latencyVerdict(150).advice).toBeUndefined();
    expect(latencyVerdict(400).advice).toBeUndefined();
  });
});

describe('usageVerdict', () => {
  it('classifica uso de conexões', () => {
    expect(usageVerdict(10).label).toBe('tranquilo');
    expect(usageVerdict(60).label).toBe('ok');
    expect(usageVerdict(80).label).toBe('elevado');
    expect(usageVerdict(95).label).toBe('crítico');
    expect(usageVerdict(80).advice).toBeTruthy();
    expect(usageVerdict(95).advice).toBeTruthy();
  });
});

describe('outboxVerdict', () => {
  it('classifica PENDING', () => {
    expect(outboxVerdict(0).label).toContain('liso');
    expect(outboxVerdict(50).label).toContain('normal');
    expect(outboxVerdict(500).label).toContain('acumulando');
    expect(outboxVerdict(5000).label).toContain('gargalo');
    expect(outboxVerdict(500).advice).toBeTruthy();
  });
});

describe('jobsVerdict', () => {
  it('0 falhas = ok; falhas = atenção com conselho', () => {
    expect(jobsVerdict(0, 5).emoji).toBe('🟢');
    expect(jobsVerdict(2, 5).emoji).toBe('🔴');
    expect(jobsVerdict(2, 5).advice).toBeTruthy();
    expect(jobsVerdict(0, 0).emoji).toBe('⚪');
  });
});

describe('salesVerdict', () => {
  it('0 vendas = calmo; cresce com o volume', () => {
    expect(salesVerdict(0).label).toContain('nenhuma venda');
    expect(salesVerdict(3).label).toContain('leve');
    expect(salesVerdict(50).label).toContain('bom movimento');
    expect(salesVerdict(500).label).toContain('pico');
  });
});

describe('chargebackVerdict', () => {
  it('0 = excelente; >0 = atenção', () => {
    expect(chargebackVerdict(0).emoji).toBe('✅');
    expect(chargebackVerdict(3).advice).toBeTruthy();
    expect(chargebackVerdict(10).emoji).toBe('🔴');
  });
});

describe('reconciliationVerdict', () => {
  it('0 = íntegro; >0 = crítica com conselho', () => {
    expect(reconciliationVerdict(0).emoji).toBe('✅');
    expect(reconciliationVerdict(2).emoji).toBe('🔴');
    expect(reconciliationVerdict(2).advice).toBeTruthy();
  });
});
