package com.foxcash.domain.port.out;

/**
 * Porta de saída: geração da senha temporária de um novo perfil master
 * (Spec 41). A senha em claro é devolvida UMA vez no create; apenas o hash
 * BCrypt é persistido.
 */
public interface MasterPasswordGenerator {

    /** Gera uma senha temporária aleatória em texto claro (ex.: {@code mst_<16 hex>}). */
    String generate();
}
