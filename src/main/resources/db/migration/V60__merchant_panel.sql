-- Spec 97: telefone opcional do lojista para o painel "Dados da Conta"
-- (a captura/edição fica na Spec 98; o cadastro Spec 39 não muda).
ALTER TABLE merchants ADD COLUMN phone VARCHAR(20);
-- null = não informado. Sem índice (acesso é por id; busca é por name/email).
