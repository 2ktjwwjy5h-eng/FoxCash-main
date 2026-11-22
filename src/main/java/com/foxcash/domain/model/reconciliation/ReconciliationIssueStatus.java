package com.foxcash.domain.model.reconciliation;

/**
 * Status de uma issue de reconciliação (Spec 30): {@code OPEN} (divergência
 * ativa, aguardando correção do master) ou {@code RESOLVED} (fechada).
 */
public enum ReconciliationIssueStatus {
    OPEN,
    RESOLVED
}
