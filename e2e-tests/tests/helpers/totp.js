// @ts-check
// Helper TOTP RFC 6238 (SHA-1, 6 dígitos, passo 30s) — mesmo algoritmo do
// backend (Totp.java, Spec 44). Extraído de auth.spec.js (Spec 71, T6.1) para
// reuso no master-console-ui.spec.js.

const nodeCrypto = require('crypto');

/**
 * TOTP para um instante (segundos Unix) — código de 6 dígitos.
 * @param {string} secretBase32
 * @param {number} unixSeconds
 * @returns {string}
 */
function totpCode(secretBase32, unixSeconds) {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  const bits = [];
  for (const ch of secretBase32.toUpperCase()) {
    const v = alphabet.indexOf(ch);
    if (v < 0) throw new Error(`base32 inválido: ${ch}`);
    for (let i = 4; i >= 0; i--) bits.push((v >> i) & 1);
  }
  const bytes = [];
  for (let i = 0; i + 8 <= bits.length; i += 8) {
    let b = 0;
    for (let j = 0; j < 8; j++) b = (b << 1) | bits[i + j];
    bytes.push(b);
  }
  const key = Buffer.from(bytes);
  const counter = Math.floor(unixSeconds / 30);
  const msg = Buffer.alloc(8);
  msg.writeBigUInt64BE(BigInt(counter));
  const hash = nodeCrypto.createHmac('sha1', key).update(msg).digest();
  const offset = hash[hash.length - 1] & 0x0f;
  const binary =
    ((hash[offset] & 0x7f) << 24) |
    ((hash[offset + 1] & 0xff) << 16) |
    ((hash[offset + 2] & 0xff) << 8) |
    (hash[offset + 3] & 0xff);
  return String(binary % 1000000).padStart(6, '0');
}

/**
 * TOTP para um deslocamento em segundos a partir de agora (clock drift).
 * Offsets DETERMINÍSTICOS na janela ±1 (RFC 6238): +30 aceito, +90/-90 rejeitado.
 * @param {string} secret
 * @param {number} offsetSeconds
 * @returns {string}
 */
function totpAt(secret, offsetSeconds) {
  return totpCode(secret, Math.floor((Date.now() + offsetSeconds * 1000) / 1000));
}

module.exports = { totpCode, totpAt };
