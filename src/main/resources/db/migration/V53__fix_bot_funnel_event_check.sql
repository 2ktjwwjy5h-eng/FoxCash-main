-- Spec 75 (correção): o CHECK de bot_funnel_events.event foi criado em V50 com
-- valores em minúsculas ('lead','checkout_started','paid'), mas o JPA persiste o
-- NOME do enum (EnumType.STRING → 'LEAD','CHECKOUT_STARTED','PAID' — mesmo padrão
-- das constraints de store_bots em V49, que já usam UPPERCASE). Todo INSERT no
-- funil violava o CHECK → 500 na compra com botId (descoberto pelo E2E
-- bot-funnel.spec.js — os unit tests mockavam o repositório e não pegavam).
--
-- Normaliza linhas existentes (defensivo — na prática a tabela fica vazia porque
-- nenhum INSERT passava) e corrige a constraint para os valores canônicos.

UPDATE bot_funnel_events SET event = 'LEAD'             WHERE event = 'lead';
UPDATE bot_funnel_events SET event = 'CHECKOUT_STARTED' WHERE event = 'checkout_started';
UPDATE bot_funnel_events SET event = 'PAID'             WHERE event = 'paid';

ALTER TABLE bot_funnel_events DROP CONSTRAINT bot_funnel_events_event_check;
ALTER TABLE bot_funnel_events ADD CONSTRAINT bot_funnel_events_event_check
    CHECK (event IN ('LEAD', 'CHECKOUT_STARTED', 'PAID'));
