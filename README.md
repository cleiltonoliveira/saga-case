# Saga POC — Arquitetura, Outbox/Inbox e descrição dos módulos

Este repositório contém uma PoC (prova de conceito) que demonstra a arquitetura Saga (orquestração central) combinada com os padrões Outbox e Inbox para garantir consistência entre microserviços usando um broker (Kafka) e um banco relacional (Postgres).

Sumário
- Visão geral da arquitetura
- Padrões Outbox / Inbox (motivação e funcionamento)
- Papéis de cada módulo (order, orchestrator, payment, inventory, common)
- Fluxo end-to-end (passo a passo)
- Tabelas do banco e como inspecioná‑las
- Como executar a PoC localmente
- Comandos de verificação (logs, Kafka, consultas SQL)
- Próximos passos e extensões

Visão geral da arquitetura
-------------------------
A PoC simula 4 "microserviços" lógicos que foram implementados como módulos Maven independentes:

- `order-service`: cria pedidos (orders) e, na mesma transação, escreve um registro no `outbox` (garante atomicidade local).
- `orchestrator`: componente orquestrador central (Saga Orchestrator). Ele faz polling do `outbox`, publica eventos no Kafka e marca mensagens como publicadas.
- `payment-service`: consome eventos `OrderCreated` do Kafka, aplica deduplicação via tabela `inbox`, e grava um registro em `payments`.
- `inventory-service`: consome eventos `OrderCreated` e simula reserva de estoque (somente logging na PoC).
- `common`: módulo compartilhado com entidades `OutboxMessage`, `InboxMessage` e repositórios comuns.

Padrões Outbox e Inbox (por que e como)
---------------------------------------
- Outbox: quando um serviço precisa produzir um evento sobre uma mudança de estado, ele grava (dentro da mesma transação do banco) um registro na tabela `outbox`. Assim, a alteração de estado e a persistência do evento são atômicas (transactional outbox). Um processo separado (o orchestrator aqui) lê a tabela `outbox` e publica os eventos no broker (Kafka). Isso evita inconsistências geradas por falhas entre DB e broker.

- Inbox: consumidores (microserviços que processam eventos) usam um mecanismo de deduplicação (`inbox`) — antes de aplicar a lógica, gravam o `messageId` recebido; se o `messageId` já estiver presente, o processamento é ignorado. Isso garante idempotência em face de reentregas (at‑least‑once delivery).

Descrição de tabelas importantes
- `orders` — pedidos com (id, amount, status, created_at)
- `outbox` — mensagens pendentes para publicar (id, aggregate_id, aggregate_type, type, payload, published, created_at)
- `inbox` — mensagens recebidas (message_id, received_at, type) para deduplicação
- `payments` — registro de pagamentos processados (id, order_id, amount, status, created_at)

Fluxo end-to-end (resumido)
1. Cliente faz POST /orders?amount=123.45 no `order-service`.
2. `order-service` inicia transação, grava `orders` e grava uma linha em `outbox` (mesma tx). Commit.
3. `orchestrator` (agendado a cada segundo) lê `outbox` onde `published=false`, publica cada evento no Kafka (tópico = `OrderCreated`) e marca `published=true`.
4. `payment-service` e `inventory-service` estão inscritos no tópico `OrderCreated`.
   - Ao receber, `payment-service` verifica `inbox` para o messageId (dedup). Se não existir, grava `inbox` e, em seguida, grava `payments` (local tx).
   - `inventory-service` processa e registra (na PoC apenas log ou poderia atualizar inventário).

Por que o orquestrador? Por simplicidade de PoC e para demonstrar o padrão outbox polling. Em arquiteturas reais, o relayer pode ser implementado por um Debezium/CDC ou um processo dedicado com garantia de entrega.

Endpoints principais (PoC)
- Criar pedido (Order Service):
  - POST /orders?amount=<valor>
  - Ex.: curl -v -X POST 'http://localhost:8081/orders?amount=123.45'

Como executar localmente (passo a passo)
-----------------------------------------
1) Subir a infra (Postgres + Zookeeper + Kafka) via Docker Compose:

```bash
sudo docker compose up -d
```

2) Build do projeto multimódulo:

```bash
mvn -DskipTests package
```

3) Executar cada serviço (em terminais separados) — duas opções:

- Rodar os jars empacotados (recomendado):

```bash
nohup java -jar order-service/target/order-service-0.0.1-SNAPSHOT.jar --spring.config.location=order-service/src/main/resources/application.yml > order.log 2>&1 & echo $! > order.pid
nohup java -jar orchestrator/target/orchestrator-0.0.1-SNAPSHOT.jar --spring.config.location=orchestrator/src/main/resources/application.yml > orchestrator.log 2>&1 & echo $! > orchestrator.pid
nohup java -jar payment-service/target/payment-service-0.0.1-SNAPSHOT.jar --spring.config.location=payment-service/src/main/resources/application.yml > payment.log 2>&1 & echo $! > payment.pid
nohup java -jar inventory-service/target/inventory-service-0.0.1-SNAPSHOT.jar --spring.config.location=inventory-service/src/main/resources/application.yml > inventory.log 2>&1 & echo $! > inventory.pid
```

- Ou usar o plugin Spring Boot (útil durante desenvolvimento):

```bash
mvn -pl order-service spring-boot:run
mvn -pl orchestrator spring-boot:run
mvn -pl payment-service spring-boot:run
mvn -pl inventory-service spring-boot:run
```

4) Disparar um pedido (exemplo):

```bash
curl -v -X POST 'http://localhost:8081/orders?amount=123.45'
```

Comandos de verificação
- Consultar o Postgres dentro do container:

```bash
sudo docker compose exec postgres psql -U saga -d sagadb -c "SELECT * FROM orders;"
sudo docker compose exec postgres psql -U saga -d sagadb -c "SELECT * FROM outbox;"
sudo docker compose exec postgres psql -U saga -d sagadb -c "SELECT * FROM inbox;"
sudo docker compose exec postgres psql -U saga -d sagadb -c "SELECT * FROM payments;"
```

- Listar tópicos Kafka (dentro do container kafka):

```bash
sudo docker compose exec kafka bash -lc "kafka-topics --bootstrap-server localhost:9092 --list"
```

- Tail dos logs dos serviços (ex.: order.log, orchestrator.log, payment.log, inventory.log) para ver publicações/consumos e polling do outbox:

```bash
tail -f order.log orchestrator.log payment.log inventory.log
```

Design e decisões importantes na PoC
----------------------------------
- Atomicidade local: escrever `orders` e `outbox` na mesma transação evita perder eventos quando a aplicação falha imediatamente após o commit do DB.
- Publicação assíncrona: o `orchestrator` garante desacoplamento entre a gravação de estado e a publicação no broker.
- Deduplicação no consumidor: `inbox` evita reprocessamento quando o Kafka reentrega mensagens (at‑least‑once).
- Simplicidade: serviço `orchestrator` centraliza lógica de publicação; em produção você pode usar um agente de CDC, change streams ou um processo mais robusto (com monitoramento/metrics).

Possíveis melhorias / próximos passos
------------------------------------
- Gerar imagens Docker por serviço e estender `docker-compose.yml` para rodar tudo em containers isolados (verdadeira simulação de microserviços).
- Implementar lógica de compensação na orquestração (ex.: se `payment` falhar, enviar evento de cancelamento para `order-service`).
- Usar schemas avro/JSON Schema e versionamento de eventos.
- Adicionar testes end‑to‑end com Testcontainers (Postgres + Kafka) para CI.
- Adicionar métricas e alertas (ex.: prometheus + grafana) para monitorar outbox backlog e falhas.

Problemas comuns e troubleshooting
---------------------------------
- Erro de conexão com Postgres: verifique se o container `postgres` está rodando e se as credenciais em `application.yml` são as mesmas (usuário: `saga`, senha: `saga`, DB: `sagadb`).
- Kafka não disponível: verifique logs do container `kafka` e `zookeeper` e confirme `bootstrap-servers: localhost:9092` nas configs.
- Repositórios JPA não encontrando entidades: os serviços usam `@EntityScan`/`@EnableJpaRepositories` para apontar para o módulo `common`.

Contatos / referências
- Este projeto é uma PoC; para referências de produção veja: "Transactional Outbox Pattern" (de Martin Fowler e posts sobre Saga Patterns), Debezium (CDC), e documentação oficial do Spring Cloud Stream / Kafka.
