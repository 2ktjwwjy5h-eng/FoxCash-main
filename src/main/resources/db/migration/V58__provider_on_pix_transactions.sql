-- Spec 91 — fallback automático de adquirente: pix_transactions.provider.
--
-- O adquirente que CRIOU a cobrança (via PixProviderRouter — roteamento
-- pós-fallback). null = legado/mock sem router (comportamento atual).
-- Usado pela reconciliação/webhook para direcionar o provedor correto.
ALTER TABLE pix_transactions ADD COLUMN provider VARCHAR(16);
-- Sem índice dedicado: o acesso é por transaction id / external_reference
-- (já indexados) — provider é metadado de origem, não chave de busca.
