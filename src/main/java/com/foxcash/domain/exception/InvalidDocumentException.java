package com.foxcash.domain.exception;

/**
 * Documento (CPF/CNPJ) com formato ou dígitos verificadores inválidos —
 * mapeada para 400 invalid_argument (Spec 18, KYC políticas §3.1).
 */
public class InvalidDocumentException extends RuntimeException {

    public InvalidDocumentException() {
        super("Documento invalido (CPF/CNPJ com digitos verificadores incorretos)");
    }
}
