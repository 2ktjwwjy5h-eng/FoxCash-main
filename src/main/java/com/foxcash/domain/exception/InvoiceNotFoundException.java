package com.foxcash.domain.exception;

import java.util.UUID;

/**
 * Fatura inexistente (ou de outro lojista) — usada na cobranca manual do
 * master (Spec 33). Mapeado para 404 {@code invoice_not_found}.
 */
public class InvoiceNotFoundException extends RuntimeException {

    public InvoiceNotFoundException(UUID invoiceId) {
        super("Invoice " + invoiceId + " not found");
    }
}
