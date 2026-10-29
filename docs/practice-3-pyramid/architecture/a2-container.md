# A2. C4 Level 2 - Container

## Что было дополнено

Не было понятно где синхронное и асинхронное взаимодействие. Для Level 2 здесь взяла существующая [`docs/architecture-diagram.puml`](../../architecture-diagram.puml). Я сохранила её контейнеры, проверила связи по коду и дополнила следующее:

- разделила sync HTTP/JDBC и async AMQP
- уточнила маршруты, exchange, queue и routing key
- добавила отсутствующие связи Switch к Merchant Acquirer, Terminal к Card Management и Merchant Acquirer к PostgreSQL.

В базовом докере 10 сервисов. Есть технический `autoscaler` запускается только в профиле `observability`, поэтому в бизнес-контур не стала включать.

Сплошные стрелки ниже означают HTTP/JDBC/WebSocket, пунктирные - асинхронный AMQP.

```mermaid
flowchart TB
    terminal[Terminal Simulator]
    merchant[Merchant Acquirer]
    dashboard[Web Dashboard]
    gateway[Gateway]
    switch[Switch]
    auth[Authorization]
    cms[Card Management]
    bin[BIN Lookup]
    logger[Transaction Logger]
    notification[Notification Service]
    db[(PostgreSQL)]
    rabbit[(RabbitMQ)]

    terminal -->|POST /api/transactions| gateway
    terminal -->|GET /api/cards| cms
    merchant -->|POST /api/transactions| gateway
    dashboard -->|REST| gateway
    dashboard <-->|WebSocket| logger

    gateway -->|POST /api/internal/route| switch
    gateway -->|/api/cards/**| cms
    gateway -->|/api/transactions/** и /api/dashboard/**| logger
    gateway -->|Simulator API| terminal
    gateway -->|Simulator API| merchant

    switch -->|authorize / rollback| auth
    switch -->|POST /api/simulator/merchant/fee| merchant
    auth -->|GET /api/bin/\{bin\}| bin
    auth -->|get / reserve / rollback| cms

    auth -->|JDBC: limit_usage| db
    cms -->|JDBC: cards, reservations, outbox_event| db
    logger -->|JDBC: transactions| db
    notification -->|JDBC: card_notifications| db
    merchant -->|JDBC: merchants, terminals, acquirer_fee| db

    switch -.->|smp.transactions<br/>transaction.log| rabbit
    rabbit -.->|queue transaction-log| logger
    cms -.->|outbox -> smp.card-events<br/>card.*| rabbit
    rabbit -.->|queue card-notifications| notification
```

## Верификация по коду

| Что проверено | Подтверждение в коде | Что уточнено на схеме |
|---|---|---|
| Состав сервисов, порты и подключения | [`docker-compose.yaml`](../../../docker-compose.yaml) | В базовом профиле находятся 10 прикладных сервисов. `autoscaler` запускается отдельно в профиле `observability`, поэтому не включён в основной бизнес-контур |
| Terminal Simulator -> Gateway | [`GatewayClient.java`](../../../services/terminal-simulator/src/main/java/com/processing/terminalsimulator/client/GatewayClient.java) | Показан синхронный вызов `POST /api/transactions` |
| Terminal Simulator -> Card Management | [`GatewayClient.java`](../../../services/terminal-simulator/src/main/java/com/processing/terminalsimulator/client/GatewayClient.java), [`docker-compose.yaml`](../../../docker-compose.yaml) | Добавлено получение тестовых карт через `GET /api/cards` - этой связи не было в исходной схеме |
| Merchant Acquirer -> Gateway | [`GatewayClient.java`](../../../services/merchant-acquirer/src/main/java/com/processing/merchantacquirer/client/GatewayClient.java) | Подтверждена отправка транзакций через Gateway |
| Gateway -> Switch, Card Management, Logger и симуляторы | [`application.yml`](../../../services/gateway/src/main/resources/application.yml) | Вместо общей стрелки подписаны фактические маршруты Gateway |
| Switch -> Authorization | [`RouteService.java`](../../../services/switch/src/main/java/com/processing/service/RouteService.java), [`AuthorizationClient.java`](../../../services/switch/src/main/java/com/processing/service/AuthorizationClient.java) | Показаны синхронные HTTP-вызовы авторизации и rollback |
| Switch -> Merchant Acquirer | [`RouteService.java`](../../../services/switch/src/main/java/com/processing/service/RouteService.java), [`MerchantAcquirerClient.java`](../../../services/switch/src/main/java/com/processing/service/MerchantAcquirerClient.java) | Добавлен отсутствующий в исходной схеме запрос комиссии эквайера |
| Authorization -> Card Management и BIN Lookup | [`CardManagementClientImpl.java`](../../../services/authorization/src/main/java/com/processing/authorization/client/CardManagementClientImpl.java), [`BinLookupClient.java`](../../../services/authorization/src/main/java/com/processing/authorization/client/BinLookupClient.java) | Подтверждены синхронные HTTP-вызовы получения карты, резервирования, rollback и поиска BIN |
| Switch -> RabbitMQ -> Transaction Logger | [`LoggerClient.java`](../../../services/switch/src/main/java/com/processing/service/LoggerClient.java), [`TransactionLogListener.java`](../../../services/transaction-logger/src/main/java/com/processing/transactionlogger/listener/TransactionLogListener.java) | Связь отмечена как асинхронная; указаны exchange `smp.transactions`, routing key `transaction.log` и очередь `transaction-log` |
| Card Management -> RabbitMQ -> Notification | [`OutboxProcessor.java`](../../../services/card-management/src/main/java/com/processing/cardmanagement/services/OutboxProcessor.java), [`OutboxEventProcessorImpl.java`](../../../services/card-management/src/main/java/com/processing/cardmanagement/services/OutboxEventProcessorImpl.java), [`CardEventListener.java`](../../../services/notification-service/src/main/java/com/processing/notification/listener/CardEventListener.java) | Показан асинхронный outbox-путь через exchange `smp.card-events` и очередь `card-notifications` |
| Dashboard -> Gateway и Transaction Logger | [`nginx.conf`](../../../services/dashboard/nginx.conf), [`WebSocketConfig.java`](../../../services/transaction-logger/src/main/java/com/processing/transactionlogger/websocket/WebSocketConfig.java) | REST-вызовы отделены от WebSocket-связи |
| Подключение сервисов к PostgreSQL | [`docker-compose.yaml`](../../../docker-compose.yaml) и JPA-сущности сервисов | Уточнено, какие сервисы сохраняют карты, лимиты, транзакции, уведомления и данные мерчантов |
