import { describe, expect, it, vi } from 'vitest';
import { Dedup } from '../src/dedup';

describe('Dedup (Spec 52)', () => {
  it('primeira ocorrência é nova; repetida não é', () => {
    const dedup = new Dedup();
    expect(dedup.isNew('evt-1')).toBe(true);
    expect(dedup.isNew('evt-1')).toBe(false);
  });

  it('eventos diferentes são todos novos', () => {
    const dedup = new Dedup();
    expect(dedup.isNew('a')).toBe(true);
    expect(dedup.isNew('b')).toBe(true);
  });

  it('expira após o TTL (reprocessa evento antigo — best-effort)', () => {
    const now = vi.fn(() => 1_000_000);
    const dedup = new Dedup(10 * 60_000);
    expect(dedup.isNew('evt', now())).toBe(true);
    // ainda dentro do TTL
    expect(dedup.isNew('evt', now() + 9 * 60_000)).toBe(false);
    // passou o TTL → novo de novo
    expect(dedup.isNew('evt', now() + 11 * 60_000)).toBe(true);
  });

  it('size reflete as entradas vivas', () => {
    const dedup = new Dedup();
    dedup.isNew('a');
    dedup.isNew('b');
    expect(dedup.size).toBe(2);
  });

  it('purge remove expirados no próximo registro', () => {
    const now = vi.fn(() => 1_000_000);
    const dedup = new Dedup(10 * 60_000);
    dedup.isNew('a', now());
    dedup.isNew('b', now());
    // registra um novo após o TTL → purge limpa a e b
    dedup.isNew('c', now() + 11 * 60_000);
    expect(dedup.size).toBe(1);
  });
});
