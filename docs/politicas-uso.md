# TERMOS DE USO E CONDIÇÕES DE SERVIÇO — FOXCASH

**Última atualização:** 17 de agosto de 2026

Bem-vindo ao FoxCash! A Plataforma é operada por FoxCash Pay ("FoxCash"), com sede e canais oficiais indicados em seu portal. O encarregado de dados (DPO) pode ser contatado via dpo@foxcash.example.com.

Estes Termos de Uso e Condições de Serviço regem de forma vinculante a relação entre o FoxCash e o Cliente ("Lojista") — pessoa física ou jurídica devidamente identificada no cadastro que utiliza a Plataforma como infraestrutura tecnológica para recebimento de pagamentos instantâneos (Pix), emissão de cobranças, gestão de subcontas, carteira digital, saques e integrações via API. Ao se cadastrar, acessar o painel ou consumir as APIs, o Cliente declara ter lido, compreendido e aceitado integralmente estes Termos e seus Anexos.

ESTA É UMA RELAÇÃO ESTRITAMENTE EMPRESARIAL (B2B). O Cliente utiliza a Plataforma como insumo de sua atividade econômica profissional, não se aplicando as disposições protetivas do Código de Defesa do Consumidor (Lei nº 8.078/90, art. 2º), ressalvadas normas cogentes de ordem pública.

## CLÁUSULA PRIMEIRA — DEFINIÇÕES E ANEXOS

1.1. Para os fins destes Termos, aplicam-se as seguintes definições:

**Plataforma:** Ecossistema tecnológico composto por painel web, APIs, webhooks, sistemas de roteamento, carteira digital e serviços agregados.

**Cliente / Lojista:** Comerciante, infoprodutores, criadores de conteúdo, prestadores de serviços ou empresas titulares da Conta na Plataforma.

**Pagador:** Pessoa física ou jurídica final que efetua pagamentos Pix direcionados ao Cliente através da infraestrutura intermediada.

**Conta:** Ambiente lógico e isolado do Cliente, acessível mediante credenciais e Chaves de API (API Keys).

**Taxa:** Remuneração devida pelo Cliente ao FoxCash pela prestação dos serviços de tecnologia e roteamento, calculada percentualmente ou por valor fixo sobre as Transações Aprovadas.

**Transação Aprovada:** Pagamento Pix confirmado e liquidado com sucesso pelo gateway/PSP parceiro.

**MED (Mecanismo Especial de Devolução):** Mecanismo regulatório do Banco Central do Brasil para contestação e reversão de Pix por suspeita de fraude ou falha operacional.

**Anexos:** Documentos complementares, incluindo Política de Privacidade, Política de Prevenção à Fraude e Manuais de Integração.

## CLÁUSULA SEGUNDA — CADASTRO, VERIFICAÇÃO (KYC) E CREDENCIAIS

2.1. O cadastro exige o fornecimento de dados verídicos, completos e atualizados. O e-mail cadastrado constitui o canal primário e identificador único de autenticação e comunicação legal.

2.2. O FoxCash realiza procedimentos contínuos de verificação de identidade e Compliance (KYC/KYB), podendo exigir, a qualquer tempo, documentos societários, comprovação de faturamento, contratos de prestação de serviços final aos pagadores, documentos pessoais dos sócios e comprovação de origem lícita de recursos.

2.3. A recusa no envio de documentos, a inconsistência cadastral ou a identificação de perfil de alto risco não tolerado conferem ao FoxCash o direito potestativo de recusar o cadastro, suspender preventivamente a Conta ou encerrá-la sumariamente.

2.4. As Chaves de API (API Keys) são exibidas em texto plano uma única vez no momento da geração. O FoxCash armazena exclusivamente hashes criptográficos dessas chaves.

2.5. O Cliente é o único e exclusivo guarda de suas senhas, tokens de autenticação e Chaves de API. Quaisquer operações executadas por meio de tais credenciais presumem-se legítimas, autorizadas e de inteira responsabilidade do Cliente, ainda que realizadas por terceiros por descuido ou vazamento de credenciais.

## CLÁUSULA TERCEIRA — MODELO COMERCIAL, TAXAS E LIQUIDAÇÃO FINANCEIRA

3.1. As Taxas aplicáveis serão deduzidas automaticamente do montante bruto das Transações Aprovadas no momento da liquidação ou disponibilizados na carteira digital do Cliente.

3.2. O FoxCash poderá revisar e reajustar as tabelas de taxas mediante aviso prévio de 5 (cinco) dias por e-mail ou na área logada. A continuidade da utilização da Plataforma após o prazo configurará concordância tácita.

3.3. Os saques e transferências de saldo da carteira digital estão sujeitos a janelas operacionais, limites de movimentação diária, tarifas bancárias e disponibilidade dos arranjos Pix.

3.4. Retenção Preventiva e Compensação (Set-off): O FoxCash poderá reter preventivamente saldos da carteira, suspender saques ou estornar valores nos seguintes casos:

- Suspeita fundamentada de fraude, lavagem de dinheiro ou operações simuladas;
- Volume atípico de chargebacks, contestações emocionais ou reclamações de pagadores;
- Ordens judiciais, administrativas ou regulatórias.

3.5. Os saldos retidos poderão ser utilizados pelo FoxCash para amortizar perdas, multas aplicadas por parceiros financeiros, custos operacionais ou ressarcimento de vítimas de fraudes comprovadas originadas na operação do Cliente.

## CLÁUSULA QUARTA — ARQUITETURA, ROTEAMENTO E LIMITAÇÕES DE TERCEIROS

4.1. O FoxCash atua como agregador e provedor de tecnologia de meio de pagamento, integrando APIs de instituições financeiras, gateways e Provedores de Serviços de Pagamento (PSPs) devidamente autorizados pelo Banco Central do Brasil.

4.2. O Cliente reconhece que o processamento de pagamentos depende da estabilidade de infraestruturas de terceiros, redes de telecomunicação e do próprio Sistema de Pagamentos Instantâneos (SPI) do Banco Central. O FoxCash não garante disponibilidade ininterrupta (100%) e não se responsabiliza por instabilidades sistêmicas externas.

## CLÁUSULA QUINTA — USO ACEITÁVEL E CONDUTAS VEDADAS

5.1. É terminantemente proibido utilizar o FoxCash para fins ilícitos, fraudulentos ou que violem leis nacionais e internacionais. Constituem infrações graves e condutas vedadas:

- A comercialização de qualquer produto ou serviço que envolva exploração sexual infantil, pornografia infanto-juvenil, aliciamento, exploração de vulneráveis ou conteúdos não consensuais de qualquer natureza (política de tolerância zero, com comunicação imediata às autoridades competentes).
- Práticas de lavagem de dinheiro, ocultação de bens, financiamento ao terrorismo ou uso de estruturas de "laranjas" para mascarar o beneficiário final.
- Operações simuladas, transações de teste sem lastro comercial real, autofinanciamento ou fraudes de reembolso.
- Inserção de cargas maliciosas, ataques de força bruta, varreduras de vulnerabilidade, injeção de código (SQLi, XSS, RCE), engenharia reversa ou tentativas de burlar os mecanismos de WAF, Turnstile e fingerprinting da Plataforma.

5.2. Dos Produtos Digitais, Comunidades e Infoprodutos:

A utilização da Plataforma para recebimento de pagamentos relacionados a produtos digitais, assinaturas de comunidades, cursos, softwares e conteúdos exclusivos é permitida, DESDE QUE o Cliente cumpra cumulativamente os seguintes requisitos de Compliance:

a) Comprovação de capacidade civil e regularidade cadastral de todos os titulares e beneficiários finais vinculados à Conta.

b) Garantia expressa de que todo o material comercializado possui direitos autorais, licenças ou autorizações necessárias, sem violação de propriedade intelectual de terceiros.

c) Cumprimento rigoroso das políticas de prevenção a fraudes, transparência na oferta ao comprador, mitigação de contestações (MEDs) e normas exigidas pelos parceiros financeiros e arranjos de pagamento.

O descumprimento das regras de compliance, a identificação de MEDs recorrentes ou o atendimento insatisfatório a reclamações de pagadores autoriza o FoxCash a reter saldos e rescindir a Conta imediatamente.

## CLÁUSULA SEXTA — SEGURANÇA, ANTIFRAUDE, BLOQUEIO IMEDIATO E SANÇÕES

6.1. O ecossistema opera com camadas de segurança automatizadas, incluindo inspeção de tráfego em borda, WAF, análise comportamental, fingerprint de dispositivos e validação de risco transacional.

6.2. Bloqueio e Rescisão Imediata: O FoxCash reserva-se o direito de bloquear preventivamente o acesso, paralisar transferências/saques e rescindir o contrato de forma imediata e sem aviso prévio, caso identifique indícios veementes de fraude, risco sistêmico iminente, lavagem de dinheiro, violação da Cláusula Quarta ou Quinta, ou ordem de autoridade competente.

6.3. As decisões automatizadas de segurança visam proteger o ecossistema financeiro como um todo. O Cliente poderá solicitar esclarecimentos e revisão por operador humano através dos canais de suporte oficiais.

## CLÁUSULA SÉTIMA — RESPONSABILIDADES E LIMITAÇÃO DE INDENIZAÇÃO

7.1. O Cliente é o único e exclusivo responsável pela regularidade fiscal, jurídica e comercial dos produtos e serviços que vende, pela emissão de notas fiscais, pelo atendimento direto aos seus Pagadores e pela resolução de conflitos de consumo.

7.2. O FoxCash não é parte da cadeia de consumo entre o Cliente e seus Pagadores, não respondendo por vícios de qualidade, entrega, garantia ou propostas comerciais ofertadas pelo Lojista.

7.3. Limitação de Responsabilidade: Em qualquer hipótese de responsabilização judicial ou arbitral imputada ao FoxCash, a indenização máxima devida ao Cliente limitar-se-á ao valor total efetivamente pago pelo Cliente ao FoxCash a título de Taxas nos últimos 3 (três) meses anteriores ao evento danoso, excluindo-se lucros cessantes, danos morais ou prejuízos indiretos.

7.4. Indenidade: O Cliente obriga-se a defender, blindar e manter o FoxCash, seus controladores, diretores, empregados e parceiros indenes de qualquer reclamação, processo, multa, sanção regulatória ou condenação decorrente de ato ilícito praticado pelo Cliente ou descumprimento destes Termos.

## CLÁUSULA OITAVA — PROTEÇÃO DE DADOS (LGPD)

8.1. O tratamento de dados pessoais rege-se pela Política de Privacidade da Plataforma. O FoxCash atua como Controlador em relação aos dados cadastrais dos Lojistas e como Operador/Controlador autônomo estritamente para fins de prevenção à fraude, segurança da informação, atendimento a ordens do Banco Central e gestão de MED no que tange aos dados dos Pagadores.

8.2. O Cliente garante possuir base legal adequada (art. 7º da Lei nº 13.709/2018) para coletar e transmitir dados de seus clientes finais à Plataforma, assumindo total responsabilidade perante a ANPD e titulares.

## CLÁUSULA NONA — DISPOSIÇÕES GERAIS E FORO

9.1. O FoxCash poderá atualizar estes Termos a qualquer momento, notificando os Clientes por e-mail ou aviso no painel. O uso continuado após a alteração implica aceitação integral.

9.2. Estes Termos são regidos pelas leis da República Federativa do Brasil.

9.3. As partes elegem o Foro da Comarca do Rio de Janeiro, Estado do Rio de Janeiro, com expressa renúncia a qualquer outro, por mais privilegiado que seja, para dirimir quaisquer litígios oriundos deste instrumento.

Ao clicar em "Aceitar", cadastrar-se ou movimentar valores na Plataforma, o Cliente manifesta anuência expressa, irrevogável e consciente a todas as cláusulas acima.
