# Тест-кейсы и анализ пирамиды

Тест-кейсы составлены по шести диаграммам и подтверждённым связям в коде.

## Часть (б): новые тест-кейсы

### Sync-проверки

| ID | Связанное требование | Уровень пирамиды | Вид | Предусловие | Шаги | Ожидаемый результат | Источник |
|---|---|---|---|---|---|---|---|
| P3-01 | Gateway ТЗ, приём транзакций | unit | негативный | Создан `TransactionRequestValidator`; запрос содержит `amount=0` | 1. Передать запрос в `validate()` | Выброшен `TransactionValidationException`; запрос не передаётся дальше | ГЗ: сумма; `TransactionRequestValidator.validate()` |
| P3-02 | Switch ТЗ, маршрутизация по BIN | unit | негативный | `RoutingService` создан с таблицей BIN `400000 → ISS001` | 1. Вызвать `getIssuerIdByPan()` с PAN, начинающимся на `999999` | Выброшен `UnknownBinException`; Authorization не вызывается | КЭ: неизвестный BIN; `RoutingService.getIssuerIdByPan()` |
| P3-03 | Authorization ТЗ, проверка статуса | unit | негативный | CMS client возвращает карту `BLOCKED`; остальные зависимости замоканы | 1. Вызвать `AuthServiceImpl.authorize()` | Ответ `DECLINED`, причина `CARD_BLOCKED`, код `05`; limit repository и reserve не вызываются | КЭ: BLOCKED; `AuthServiceImpl.authorize()` |
| P3-04 | Card Management ТЗ, резервирование | unit | позитивный | ACTIVE-карта с балансом 1000 | 1. Создать reservation на 1000. 2. Применить `withReservation()` | Reservation имеет `RESERVED`; новый баланс карты равен 0 | ГЗ: сумма равна балансу; `Card.startReservation()` |
| P3-05 | Card Management ТЗ, создание и GET | api | позитивный | Card Management запущен; BIN существует; БД очищена от тестового PAN | 1. `POST /api/cards` с валидным телом. 2. Получить созданный PAN. 3. `GET /api/cards/{pan}` | POST возвращает 201; GET возвращает 200 и ту же карту со статусом ACTIVE | Контракт CRUD; `CardController.createCard()/getCard()` |
| P3-06 | Authorization ТЗ, карта не найдена | api | негативный | Authorization запущен; CMS stub отвечает 404 на GET карты | 1. `POST /api/internal/authorize` с валидным запросом | HTTP 404; тело содержит `DECLINED`, `responseCode=14`, `CARD_NOT_FOUND` | Контракт HTTP и mapping; `AuthControllerImpl.authorize()` |
| P3-07 | Card Management ТЗ, PATCH | api | позитивный | Существует ACTIVE-карта с известными лимитами и балансом | 1. `PATCH /api/cards/{pan}` только с новым `dailyLimit`. 2. Получить карту | HTTP 200; изменён только dailyLimit, остальные изменяемые поля сохранены | Контракт PATCH; `CardController.patchCard()` |
| P3-08 | Authorization ТЗ, дневной лимит | integration | негативный | PostgreSQL запущен; в `limit_usage` дневной расход 999; лимит 1998; сумма новой операции 1000 | 1. Выполнить `upsertLimitUsage()` для той же карты и даты | Обновлено 0 строк; накопленная сумма не меняется; Authorization может вернуть код 61 | ГЗ + состояние БД; `LimitUsageRepository.upsertLimitUsage()` |

### Async и сквозные проверки

| ID | Связанное требование | Уровень пирамиды | Вид | Предусловие | Шаги | Ожидаемый результат | Источник |
|---|---|---|---|---|---|---|---|
| P3-09 | Switch/Logger: доставка транзакции | integration | позитивный | RabbitMQ, Logger и PostgreSQL запущены; очередь пуста | 1. Опубликовать уникальный `TransactionRequest` в `smp.transactions` с key `transaction.log`. 2. Ожидать запись по UUID с ограниченным timeout | Сообщение получено из `transaction-log`; в БД ровно одна соответствующая транзакция | Async-контракт + eventual consistency; `TransactionLogListener` |
| P3-10 | Card Management: outbox → Notification | integration | позитивный | CMS, RabbitMQ, Notification и PostgreSQL запущены | 1. Создать карту через CMS. 2. Ожидать `PROCESSED` у outbox-события. 3. Ожидать notification нужного типа | Outbox-событие сохранено и опубликовано; Notification сохраняет одну запись с routing key `card.CardServiceCreationEvent` | Состояние БД + async-сценарий |
| P3-11 | RabbitMQ DLX/DLQ и TTL | integration | негативный | RabbitMQ запущен; consumer стабильно выбрасывает исключение; DLQ очищена | 1. Отправить невалидное сообщение. 2. Наблюдать повторные доставки. 3. Проверить DLQ. 4. Подождать более 60 s | После настроенного delivery limit сообщение находится в DLQ; после TTL исчезает. Если перехода после 3 доставок нет - фиксируется дефект конфигурации | Топология RabbitMQ; `RabbitMQConfig` |
| P3-12 | Switch ТЗ, publisher-confirm и reversal | integration | негативный | APPROVED уже получен; broker настроен дать NACK либо не дать confirm; reservation и исходный баланс известны | 1. Вызвать `RouteService.route()`. 2. Дождаться confirm timeout 2 s. 3. Проверить rollback и баланс | По ТЗ вызывается rollback, баланс восстановлен, клиент получает `DECLINED/96`. На текущем коде ожидается обнаружение дефекта: ACK/NACK не ожидается | Сценарий отказа; publisher confirm; состояние БД |
| P3-13 | Критический путь покупки | e2e | позитивный | Весь базовый контур запущен; существует ACTIVE-карта с достаточными лимитами и балансом | 1. Отправить покупку через Gateway. 2. Проверить HTTP-ответ. 3. Ожидать запись Logger. 4. Проверить уменьшение баланса | Клиент получает APPROVED/00; баланс уменьшен один раз; Logger позднее содержит APPROVED-транзакцию | Сквозной сценарий + eventual consistency |
| P3-14 | Критический declined path | e2e | негативный | Весь базовый контур запущен; карта BLOCKED; исходный баланс известен | 1. Отправить покупку через Gateway. 2. Проверить ответ и баланс. 3. Ожидать запись Logger | Клиент получает DECLINED/CARD_BLOCKED; баланс не меняется; Logger позднее содержит DECLINED-транзакцию | Состояние Card + сквозной сценарий |

Итого новых кейсов: 14. Покрыты уровни `unit`, `api`, `integration`, `e2e`; sync и async-пути.

## Часть (в): распределение тест-кейсов практики №2

### Authorization - классы эквивалентности и границы

| ID (пр. 2) | Название | Что проверяет тест | Уровень | Обоснование |
|---|---|---|---|---|
| A-01 | Успешная авторизация | Решение и реальное резервирование в CMS | e2e | Ожидание пересекает Gateway/Switch/Auth/CMS |
| A-02 | Карта не найдена | Mapping ответа CMS 404 в decline 14 | api | Проверяется межсервисный HTTP-контракт |
| A-03 | Карта INACTIVE | Ветка статуса карты | unit | Достаточно AuthService и mock CMS |
| A-04 | Карта BLOCKED | Ветка статуса карты | unit | Изолированная бизнес-логика |
| A-05 | Статус EXPIRED | Ветка статуса карты | unit | Изолированная бизнес-логика |
| A-06 | Истёкший expiryDate | Сравнение даты с месяцем операции | unit | Чистая логика AuthService |
| A-07 | Дневной лимит превышен | Атомарное условие daily amount | integration | Правило находится в native PostgreSQL query |
| A-08 | Месячный лимит превышен | Накопление monthly amount | integration | Требуется реальная БД и предыдущие записи |
| A-09 | Недостаточно средств | Сравнение amount и availableBalance | unit | Чистое ветвление сервиса |
| A-10 | CMS недоступен | HTTP error mapping и decline | api | Проверяется поведение на границе HTTP |
| A-11 | BIN fallback | Graceful degradation BIN client | unit | Ошибку клиента можно воспроизвести test double |
| A-12 | Дневной остаток 999 | Нижняя сторона границы daily limit | integration | Реальное условие SQL update |
| A-13 | Дневной остаток 1000 | Точное равенство daily limit | integration | Реальное условие SQL update |
| A-14 | Дневной остаток 1001 | Запас относительно daily limit | integration | Реальное состояние БД |
| A-15 | Месячный остаток 999 | Нижняя сторона monthly limit | integration | Накопление между строками БД |
| A-16 | Месячный остаток 1000 | Точное равенство monthly limit | integration | Нужен PostgreSQL upsert |
| A-17 | Месячный остаток 1001 | Запас monthly limit | integration | Нужен PostgreSQL upsert |
| A-18 | Баланс 999 | Недостаточный баланс на границе | unit | BigDecimal compare в AuthService/Card |
| A-19 | Баланс 1000 | Сумма равна балансу | unit | Доменная арифметика |
| A-20 | Баланс 1001 | Баланс с минимальным запасом | unit | Доменная арифметика |
| A-21 | Прошлый месяц | Expiry boundary - expired | unit | Чистое сравнение даты |
| A-22 | Текущий месяц | Карта действительна до конца месяца | unit | Чистое сравнение даты |
| A-23 | Следующий месяц | Валидный будущий срок | unit | Чистое сравнение даты |

### Authorization - pairwise и дополнительные

| ID (пр. 2) | Название | Что проверяет тест | Уровень | Обоснование |
|---|---|---|---|---|
| A-PW-01 | INACTIVE, BIN unavailable | Приоритет статуса INACTIVE | unit | Ветвление AuthService с mocks |
| A-PW-02 | Все границы equal | Полный позитивный pairwise путь | e2e | Ожидается фактический reserve |
| A-PW-03 | Daily above | Приоритет превышения daily | integration | Итог native SQL зависит от БД |
| A-PW-04 | Expired date | Приоритет срока действия | unit | До обращения к лимитам |
| A-PW-05 | Current/equal позитивный | Позитивный путь с BIN fallback | e2e | Проверяется reserve через сервисы |
| A-PW-06 | Monthly above | Отказ по месячному лимиту | integration | Нужна история monthly usage |
| A-PW-07 | Daily above, mixed limits | Отказ по daily limit | integration | Проверяется SQL predicate |
| A-PW-08 | Статус EXPIRED | Приоритет enum EXPIRED | unit | Изолированная ветка |
| A-PW-09 | Статус BLOCKED | Приоритет BLOCKED | unit | Изолированная ветка |
| A-PW-10 | Daily above, BIN unavailable | Daily decline при fallback | integration | Ключевой результат задаёт БД |
| A-PW-11 | Balance above | Отказ по балансу | unit | Проверка выполняется до SQL limits |
| A-PW-12 | Monthly above, BIN unavailable | Monthly decline | integration | Нужна БД |
| A-PW-13 | Balance above при equal limits | Отказ по балансу | unit | Чистая логика |
| A-PW-14 | INACTIVE, BIN available | Статус независимо от BIN | unit | Чистая логика |
| A-PW-15 | EXPIRED, BIN available | Статус независимо от BIN | unit | Чистая логика |
| A-PW-16 | BLOCKED, BIN available | Статус независимо от BIN | unit | Чистая логика |
| A-PW-17 | Daily above | Дополнительная комбинация daily | integration | Native SQL |
| A-PW-18 | Expired, BIN unavailable | Срок независимо от BIN | unit | Чистая логика |
| A-24 | BIN Lookup 5xx | Число попыток и fallback | api | Нужен управляемый HTTP stub |
| A-25 | Уникальность RRN | Конкурентная генерация через sequence | integration | Требуется PostgreSQL sequence и concurrency |

### Card Management - классы эквивалентности и границы

| ID (пр. 2) | Название | Что проверяет тест | Уровень | Обоснование |
|---|---|---|---|---|
| C-01 | Создание карты | Генерация и сохранение сущности | integration | BIN repository, JPA и БД участвуют вместе |
| C-02 | GET существующей карты | HTTP-контракт успешного GET | api | Проверяются route/status/body |
| C-03 | GET отсутствующей карты | HTTP 404 | api | Проверяется exception mapping |
| C-04 | GET удалённой карты | Hibernate soft-delete restriction | integration | Поведение задаёт JPA/БД |
| C-05 | Частичный PATCH | Контракт частичного обновления | api | Важны HTTP body и неизменённые поля |
| C-06 | Мягкое удаление | DELETED в БД и скрытие запросами | integration | Требуется фактическое состояние БД |
| C-07 | Пагинация по умолчанию | limit=50 и total | integration | Проверяется JPA pagination/count |
| C-08 | Фильтрация status+BIN | Комбинация predicates | integration | Логика Criteria API |
| C-09 | Генерация 100 карт | Массовое распределение и persistence | integration | Требуется реальная БД |
| C-10 | Резервирование | Reservation и изменение баланса | integration | Одна транзакция и несколько таблиц |
| C-11 | BIN/PAN/Luhn | Генерация валидного PAN | unit | Чистый алгоритм |
| C-12 | Срок +3 года | `Card.fromDraft()` | unit | Доменная функция |
| C-13 | Reserve всего баланса | Граница arithmetic до нуля | unit | `Card.withReservation()` без инфраструктуры |
| C-14 | Reserve при балансе 1001 | Остаток 1 | unit | Доменная арифметика |
| C-15 | Offset 3 | Пагинация внутри набора | integration | JPA offset/limit |
| C-16 | Offset равен total | Пустая страница | integration | JPA pagination |
| C-17 | Offset больше total | Пустая страница | integration | JPA pagination |

### Card Management - pairwise

| ID (пр. 2) | Название | Что проверяет тест | Уровень | Обоснование |
|---|---|---|---|---|
| C-PW-01 | Создание BIN 400000 | Создание одиночной карты | integration | Генератор + repository |
| C-PW-02 | Reserve below, ACTIVE | Доменное уменьшение баланса | unit | Чистая логика Card |
| C-PW-03 | Генерация 1, BIN 400001 | Сохранение с заданным BIN | integration | Нужна БД |
| C-PW-04 | GET BLOCKED | Контракт GET независимо от статуса | api | HTTP response |
| C-PW-05 | Reserve missing/equal | Ошибка для отсутствующего PAN | api | HTTP 404 mapping |
| C-PW-06 | PATCH DELETED | Поведение контракта для скрытой карты | api | Проверяется HTTP-ответ |
| C-PW-07 | DELETE ACTIVE | Soft delete и скрытие | integration | Состояние БД |
| C-PW-08 | Генерация 5, BIN 400000 | Массовая вставка | integration | Persistence |
| C-PW-09 | Reserve above, BLOCKED | Приоритет проверки статуса | unit | `Card.checkStatusOrThrow()` |
| C-PW-10 | GET missing | HTTP 404 | api | Контракт GET |
| C-PW-11 | Reserve equal, DELETED | Запрет операции по статусу | unit | Доменное правило |
| C-PW-12 | Создание BIN 400001 | Создание карты | integration | Repository + DB |
| C-PW-13 | PATCH missing | HTTP 404 | api | Контракт PATCH |
| C-PW-14 | DELETE missing | HTTP 404 | api | Контракт DELETE |
| C-PW-15 | Reserve missing/below | HTTP 404 | api | Контракт reserve |
| C-PW-16 | Reserve missing/above | HTTP 404 | api | Контракт reserve |
| C-PW-17 | GET ACTIVE | Успешный GET | api | HTTP-контракт |
| C-PW-18 | GET DELETED | Скрытие soft-deleted entity | integration | Hibernate restriction |
| C-PW-19 | PATCH ACTIVE | Изменение лимита | api | HTTP PATCH contract |
| C-PW-20 | PATCH BLOCKED | PATCH независимо от текущего статуса | api | HTTP-контракт |
| C-PW-21 | DELETE BLOCKED | Soft delete | integration | Изменение/скрытие в БД |
| C-PW-22 | DELETE DELETED | Повторное удаление скрытой карты | api | Наблюдаемый HTTP status |
| C-PW-23 | Генерация 1, BIN 400000 | Одиночная генерация | integration | Persistence |
| C-PW-24 | Генерация 5, BIN 400001 | Массовая генерация | integration | Persistence |
| C-PW-25 | Reserve equal, ACTIVE | Баланс становится нулём | unit | Доменная арифметика |
| C-PW-26 | Reserve above, ACTIVE | Insufficient funds | unit | Доменное исключение |
| C-PW-27 | Reserve below, BLOCKED | Запрет по статусу | unit | Доменное правило |
| C-PW-28 | Reserve equal, BLOCKED | Запрет по статусу | unit | Доменное правило |
| C-PW-29 | Reserve below, DELETED | Запрет по статусу | unit | Доменное правило |
| C-PW-30 | Reserve above, DELETED | Приоритет статуса над балансом | unit | Доменное правило |

### Card Management - список и async

| ID (пр. 2) | Название | Что проверяет тест | Уровень | Обоснование |
|---|---|---|---|---|
| C-L-01 | Список limit=2 | total, limit и скрытие DELETED | integration | Criteria/JPA/SQLRestriction |
| C-L-02 | Пустой фильтр BLOCKED+BIN | Пустой результат и count | integration | Комбинация predicates |
| C-L-03 | ACTIVE+BIN+offset | Offset после фильтрации | integration | Реальный SQL query |
| C-L-04 | BIN+limit+offset | Пагинация отфильтрованного набора | integration | Реальный SQL query |
| C-L-05 | Пустой список | Нулевой count | integration | Repository response |
| C-L-06 | BLOCKED+BIN+offset | Фильтр и offset | integration | Criteria API |
| C-L-07 | ACTIVE+BIN без данных | Пустой фильтр | integration | Criteria API |
| C-L-08 | ACTIVE+limit+offset | Count и страница | integration | JPA pagination |
| C-L-09 | BIN без данных | Пустой фильтр | integration | Repository query |
| C-L-10 | Все BLOCKED | Фильтрация статуса | integration | Repository query |
| C-L-11 | Все карты BIN | Фильтрация BIN | integration | Repository query |
| C-18 | Outbox карточных событий | Запись outbox и публикация RabbitMQ | integration | Пересекаются БД и broker |

## Часть (г): анализ пропорций

### Распределение по четырём уровням

| Уровень | Старые (пр. 2) | Новые | Итого | Доля |
|---|---:|---:|---:|---:|
| unit | 35 | 4 | 39 | 33,6% |
| api | 18 | 3 | 21 | 18,1% |
| integration | 46 | 5 | 51 | 44,0% |
| e2e | 3 | 2 | 5 | 4,3% |
| **Итого** | **102** | **14** | **116** | **100,0%** |

Формула: `доля уровня = количество кейсов уровня / 116 × 100%`.

### Сводка в формате задания

| Уровень | Старые | Новые | Итого | Доля |
|---|---:|---:|---:|---:|
| unit | 35 | 4 | 39 | 33,6% |
| api / integration | 64 | 8 | 72 | 62,1% |
| e2e | 3 | 2 | 5 | 4,3% |
| **Итого** | **102** | **14** | **116** | **100,0%** |

### Обоснование формы пирамиды

Получившееся распределение не соответствует ориентиру 70/20/10, и для этой системы это объяснимо. Значительная часть требований реализована на интеграционных границах: native PostgreSQL upsert для лимитов, pessimistic locks и JPA-фильтры в Card Management, RabbitMQ, outbox, DLQ и eventual consistency. Замена этих зависимостей mock-объектами не доказывает основное утверждение теста.

Доля E2E ограничена пятью критичными сквозными сценариями. Это снижает риск медленной и нестабильной «перевёрнутой пирамиды». При этом unit-слой остаётся существенным: в нём проверяются статусы, даты, баланс, Luhn, маршрутизация и валидация. Риск текущего распределения - дорогой integration-слой; его следует контролировать переиспользуемыми Testcontainers, изоляцией данных и bounded polling для async-проверок.

## Выявленные расхождения, влияющие на тесты

- Publisher-confirm timeout 2 s не реализован: `LoggerClient` не ожидает ACK/NACK.
- `RollbackRequest` не содержит MTI 0400; `AuthorizationRequest.forReversal()` не используется.
- Rollback возвращает баланс, но не уменьшает `limit_usage`.
- BIN Lookup response в Authorization только логируется и не заменяет issuerId.
- Outbox повторяется с fixed delay, а не exponential backoff.
- `ROLLED_BACK` - состояние Reservation, а не Transaction.
- Практика №2 ожидает `ISSUER_TIMEOUT` при недоступном CMS, но код возвращает `SERVICE_UNAVAILABLE`.
