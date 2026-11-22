import { describe, expect, it } from 'vitest';
import { maskIp } from '../src/commands.js';

describe('maskIp', () => {
  it('IPv4 mantém o último octeto (mascaramento sem markdown — •••)', () => {
    expect(maskIp('200.150.10.20')).toBe('•••.•••.•••.20');
  });

  it('IPv6 mantém os primeiros grupos + …', () => {
    expect(maskIp('2001:db8:85a3::8a2e:370:7334')).toBe('2001:db8:85a3:…');
  });

  it('IPv6 curto (loopback) vira …', () => {
    expect(maskIp('::1')).toBe('…');
  });

  it('não expõe o IP completo em nenhum formato', () => {
    for (const ip of ['200.150.10.20', '2001:db8:85a3::8a2e:370:7334', '192.168.0.1']) {
      expect(maskIp(ip)).not.toBe(ip);
    }
  });
});
