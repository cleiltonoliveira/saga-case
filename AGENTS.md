# Agents Knowledge Base — Saga POC

Este documento serve como uma base de conhecimento fixa para o agente de IA que vai atuar nesse repositório. O objetivo é fornecer contexto, convenções, pontos de integração e exemplos úteis para que o agente possa responder, modificar e testar o projeto com segurança e consistência.

SUMÁRIO
- Visão geral do projeto
- Arquitetura: Saga orquestrada + Outbox/Inbox + Kafka
- Módulos / Microserviços
- Banco de dados (PostgreSQL) e schema básico
- Mensageria (Kafka): tópicos e contratos de mensagens (JSON)
- Fluxo da Saga de exemplo (pedido)
- Padrões Outbox / Inbox — como estão aplicados
- Como rodar localmente (docker-compose + Maven)
- Comandos úteis
- Convenções de código e pastas importantes
- Testes e dicas de verificação
- Perguntas frequentes que o agente deve saber responder
- Exemplos de prompts para o agente
- Como atualizar esta base de conhecimento


1) Visão geral do projeto
-------------------------
Este repositório é uma POC (prova de conceito) convertida para um projeto Maven multi-module com microserviços separados: order-service, payment-service, inventory-service e um módulo orquestrador (orchestrator). Há também um módulo `common` com classes compartilhadas (DTOs, eventos, utilitários, configurações comuns).

Objetivo: demonstrar arquitetura Saga no modo orquestração com um orquestrador central (o módulo `orchestrator`) que coordena transações entre serviços via eventos Kafka. Para garantir consistência e resiliência frente a falhas, usa-se o padrão Outbox para escrita de eventos em cada serviço (mesma transação DB) e um mecanismo de Inbox/Consumer para deduplicação e processamento idempotente no consumo.

2) Arquitetura
--------------
- Orquestração: o `orchestrator` inicia e coordena os passos da saga (ex.: criar pedido -> reservar estoque -> processar pagamento -> confirmar pedido / compensar).
- Outbox: cada serviço grava comandos/ eventos a serem publicados numa tabela `outbox` dentro da mesma transação do negócio. Um *publisher* (separado ou integrado) lê a tabela `outbox` e publica no Kafka garantindo entrega "at-least-once".
- Inbox: cada serviço tem uma tabela `inbox` para registrar eventos recebidos e evitar processamento duplicado (idempotência). O consumidor registra o evento no `inbox` antes de aplicar a alteração de negócio.
- Mensageria: Kafka é o barramento de eventos entre os serviços.
- Persistência: PostgreSQL para armazenamento de dados de cada serviço (cada microserviço utiliza seu schema ou banco separado conforme configuração). No docker-compose o Postgres estará disponível.

3) Módulos / Microserviços
--------------------------
- root `pom.xml`: aggregator do multi-module
- `common/`: DTOs, eventos, utilitários, configurações (ex.: classes de serialização/deserialização de eventos, contratos de mensagens)
- `orchestrator/`: orquestrador da saga — responsável por iniciar sagas, enviar comandos (publicar eventos) e reagir a eventos para avançar ou compensar a saga.
- `order-service/`: expõe API HTTP para criar pedidos; mantém tabela `orders` e `outbox` com mensagens como `OrderCreated`, `OrderConfirmed`, `OrderCancelled`.
- `inventory-service/`: controla estoque; consome comandos para reservar ou liberar estoque; publica `StockReserved` / `StockReservationFailed`.
- `payment-service/`: processa pagamentos; consome pedidos de pagamento e publica `PaymentSucceeded` / `PaymentFailed`.

4) Banco de dados (PostgreSQL)
-------------------------------
- O docker-compose contém um serviço `postgres` com dados persistidos em um volume.
- Cada microserviço deve apontar para o mesmo Postgres (para POC) ou bancos separados conforme `application.yml` (ajustável).
- Tabelas mínimas por serviço (exemplos):
  - order-service: `orders`, `order_items`, `outbox`, `inbox`
  - payment-service: `payments`, `outbox`, `inbox`
  - inventory-service: `stock`, `outbox`, `inbox`
  - outbox (colunas sugeridas): `id (uuid)`, `aggregate_id`, `aggregate_type`, `payload (json)`, `type`, `created_at`, `published_at` (nullable)
  - inbox (colunas): `message_id (uuid)`, `source`, `type`, `received_at`, `processed_at` (nullable), `payload`.

5) Mensageria (Kafka)
----------------------
Tópicos demonstrativos (ajuste no docker-compose / configuração):
- orders.commands
- orders.events
- inventory.commands
- inventory.events
- payments.commands
- payments.events
- saga.orchestrator

Contratos de mensagens (JSON) - convenção
- Campo `messageId` (UUID)
- Campo `sagaId` (UUID) — id da saga para rastreamento
- Campo `type` — nome do evento
- Campo `timestamp`
- Campo `payload` — objeto com dados do evento

Exemplo `OrderCreated`:
{
  "messageId": "...",
  "sagaId": "...",
  "type": "OrderCreated",
  "timestamp": "2026-02-18T12:00:00Z",
  "payload": {
    "orderId": "...",
    "items": [{"sku":"abc","qty":2}],
    "total": 123.45
  }
}

6) Fluxo da Saga de exemplo
---------------------------
Caso de uso: Criar pedido
1. Cliente chama `order-service POST /orders`.
2. `order-service` cria um registro `orders` com status `PENDING` e grava um evento `OrderCreated` na `outbox` (mesma transação SQL).
3. Outbox-publisher publica `OrderCreated` no tópico `orders.events`.
4. `orchestrator` consome `OrderCreated`, cria/atribui `sagaId` e publica comando `ReserveStock` no tópico `inventory.commands`.
5. `inventory-service` consome `ReserveStock`. Ao iniciar consumo, insere um registro no `inbox` para impedir duplicação. Se houver estoque disponível, marca reserva e publica `StockReserved` (outbox -> Kafka). Se falha, publica `StockReservationFailed`.
6. `orchestrator` consome `StockReserved` (ou `StockReservationFailed`) e decide se continua para `Payment` ou compensa (cancela pedido / libera reservas). Para `StockReserved`, publica `ProcessPayment` para `payment.commands`.
7. `payment-service` processa e publica `PaymentSucceeded` ou `PaymentFailed`.
8. `orchestrator` consome resultado de pagamento e publica `ConfirmOrder` ou `CancelOrder`. `order-service` ao receber `ConfirmOrder` muda status para `CONFIRMED` e envia `OrderConfirmed`. Se cancelamento, `OrderCancelled`.

Compensações
- Se `StockReservationFailed` ou `PaymentFailed`, o orchestrator envia comandos de compensação: `ReleaseStock`, `CancelPayment` (se aplicável) e `CancelOrder`.

7) Padrão Outbox / Inbox — práticas e implementação
---------------------------------------------------
Outbox:
- Gravado como parte da transação de negócio.
- Um job/publisher (pode ser um componente dentro do serviço ou processo separado) lê linhas não-publicadas, publica em Kafka e atualiza `published_at`.
- Recomendação: chave única em `id` e índice em `published_at`.

Inbox:
- Ao consumir uma mensagem, registre um `message_id` no `inbox` antes de aplicar a mudança de estado.
- Se o `message_id` já existe e `processed_at` preenchido, ignore (idempotência).
- Use transações para garantir que a gravação no `inbox` e a alteração do estado do serviço sejam atômicas.

8) Como rodar localmente
------------------------
Pré-requisitos: Docker, docker-compose, JDK 17+, Maven.

Passos gerais:
1. Subir infra (Postgres + Kafka + Zookeeper) via `docker-compose up -d`.
2. Build do projeto: `mvn -T1C clean install` na raiz.
3. Iniciar cada serviço (ou todos com `mvn -pl :order-service -am spring-boot:run`, etc.) ou executar jars em `target/`.
4. Verificar tópicos Kafka com `kafka-topics` ou usar UI (se inclusa). Para testes manuais, use `curl` nas APIs REST.

Comandos úteis (exemplos):
- Build: mvn -T1C clean install
- Rodar serviço em dev: mvn -pl :order-service spring-boot:run
- Run docker-compose: docker-compose up -d
- Logs docker-compose: docker-compose logs -f

9) Convenções de código e pastas
-------------------------------
- Cada módulo contém `src/main/java` e `src/main/resources/application.yml`.
- DTOs e eventos comuns no módulo `common` (usar classes deste módulo com dependência nos outros módulos).
- Nomes de pacotes: `com.example.<service>` (manter consistência)
- Testes unitários em `src/test/java` e testes de integração em `src/it` (se houver)
- Versionamento: Maven multimodule com versionamento compartilhado pelo parent POM

10) Testes e verificação
------------------------
- Unit tests: cover happy path + falha (ex.: falta de estoque, falha de pagamento).
- Integration tests (opcional): subir infra em Docker Compose e rodar testes que falam com Kafka/Postgres.
- Checks que o agente deve executar antes de PR: `mvn -q -DskipTests=false clean test`.

11) Perguntas frequentes que o agente deve saber responder
---------------------------------------------------------
- Como iniciar a stack localmente? R: `docker-compose up -d` + `mvn install` + executar serviços.
- Onde estão os contratos de evento? R: `common/src/main/java/...` e `agents.md`.
- Como o outbox é publicado? R: job/publisher lê `outbox` e publica no Kafka; atualizar `published_at`.
- Como garantir idempotência? R: `inbox` com verificação de `messageId`.

12) Exemplos de prompts para o agente (boas práticas)
---------------------------------------------------
- "Verifique se o tópico `orders.events` é criado no docker-compose e me mostre o trecho relevante." (o agente deve procurar `docker-compose.yml`)
- "Adicione um teste unitário em order-service que simule falta de estoque e verifique que o status do pedido vira CANCELLED após a saga." (o agente deve criar um teste e executar `mvn test`)
- "Implemente um publisher de outbox simples em inventory-service usando Spring @Scheduled que publica mensagens não publicadas." (o agente deve adicionar classes e registrar no pom se necessário)

13) Como atualizar/expandir esta base de conhecimento
---------------------------------------------------
- Mantê-la em `agents.md` na raiz do repositório.
- Incluir sempre: novos tópicos Kafka, novos eventos, novos bancos ou serviços.
- Para mudanças maiores, adicionar seção "CHANGELOG" com data e resumo.

14) Fontes / arquivos de referência que o agente deve conhecer
--------------------------------------------------------------
- `pom.xml` (raiz) — estrutura multimodule
- `common/` — DTOs e contratos
- `orchestrator/src/main/java/...` — lógica da saga
- `order-service/src/main/java/...`, `payment-service/...`, `inventory-service/...` — lógicas específicas
- `docker-compose.yml` — infra (Postgres + Kafka)
- `README.md` — documentação principal do projeto

15) Restrições e cuidados
-------------------------
- Nunca expor credenciais em código (arquivos `.yml` de exemplo devem usar placeholders ou variáveis de ambiente).
- Evitar alterações drásticas sem testes automatizados.
- Ao ajustar o banco (migrations), adicionar scripts SQL ou usar tool de migrations (Flyway/Liquibase).

16) Exemplo de checklist que o agente deve seguir antes de um PR
----------------------------------------------------------------
- [ ] Executar `mvn -T1C clean install`
- [ ] Executar testes unitários
- [ ] Verificar logs do docker-compose para erros
- [ ] Checar tópicos Kafka e conectividade
- [ ] Verificar integração outbox->Kafka (mensagem publicada)

17) Notes para instrumentação e observabilidade
-----------------------------------------------
- Adicionar métricas (Prometheus) e traces (Zipkin / OpenTelemetry) é recomendado para rastrear sagas e latência entre passos.

18) Contatos e manutenção
-------------------------
- Mantenedores do repositório: (adicionar nomes/handles conforme necessário) — se não houver, manter `TODO: adicionar mantenedores`.


---

Seções futuras recomendadas:
- Exemplos reais de payloads para todos os eventos
- Scripts SQL de criação das tabelas `outbox` e `inbox`
- Políticas de retry/backoff para publicação e consumo

Última atualização: 2026-02-18

