package com.foxcash.domain.exception;

/**
 * Documento (CPF/CNPJ) ja cadastrado para outro lojista.
 *
 * <p>Mensagem genérica — não ecoa o documento (Spec 18: evitar vazamento de
 * dado pessoal em resposta de erro).
 */
public class MerchantAlreadyExistsException extends RuntimeException {

    public MerchantAlreadyExistsException() {
        super("Ja existe um lojista cadastrado com este documento");
    }
}
