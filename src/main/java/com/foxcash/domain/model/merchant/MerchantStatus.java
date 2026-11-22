package com.foxcash.domain.model.merchant;

public enum MerchantStatus {
    ACTIVE,
    SUSPENDED,
    INACTIVE,

    /** Spec 98: banimento irreversível pelo master (bloqueia API/portal/saques/cobranças). */
    BANNED
}
