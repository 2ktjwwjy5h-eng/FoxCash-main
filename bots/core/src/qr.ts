import QRCode from 'qrcode';

/**
 * PNG mágico (primeiros 8 bytes de um arquivo PNG: 89 50 4E 47 0D 0A 1A 0A).
 */
const PNG_MAGIC = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);

/**
 * PNG real do QR Code da compra (Spec 52).
 *
 * O backend devolve `pixQrCode` como base64 **PNG** (contrato), mas o mock de dev
 * (`MockPixProviderClient`) viola o contrato e devolve base64 do TEXTO do BR Code —
 * o Telegram/Discord rejeitam essa "foto" (`IMAGE_PROCESS_FAILED`). Este helper
 * normaliza: se o base64 decodificar para um PNG válido, usa direto; caso
 * contrário, **gera o QR a partir do `pixCopyPaste`** (BR Code), que o backend
 * sempre devolve correto.
 */
export async function qrPngFromPurchase(purchase: {
  pixQrCode: string;
  pixCopyPaste: string;
}): Promise<Buffer> {
  try {
    const raw = Buffer.from(purchase.pixQrCode, 'base64');
    if (raw.length > PNG_MAGIC.length && raw.subarray(0, PNG_MAGIC.length).equals(PNG_MAGIC)) {
      return raw;
    }
  } catch {
    // base64 inválido — segue para gerar a partir do BR Code
  }
  return QRCode.toBuffer(purchase.pixCopyPaste, { type: 'png', width: 512, margin: 2 });
}
