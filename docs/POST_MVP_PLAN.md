# POST_MVP_PLAN — за пределами MVP `aiProjectDashboard`

> Всё, что сознательно вынесено за рамки двухфазного MVP
> ([`PLAN.md`](./PLAN.md) §5). Порядок — примерная приоритизация, не обязательство.

---

## 1. Реальный LLM-ранкер TODO (BYOK) — СДЕЛАНО (`TECHNICAL_PLAN.md`, Этап 4)
`LlmRanker` через Ktor Client к OpenAI-совместимому API, ключ только из
переменной окружения, батч одним запросом, структурированный JSON
(`priority 1..5 + reason`), тихий откат на `HeuristicRanker` при отсутствии
ключа/сбое. См. `rank/LlmRanker.kt` + `LlmRankerTest`.

## 2. GraphQL-батчинг статуса — СДЕЛАНО
`GhCliClient.fetchStatuses` строит один GraphQL-запрос (алиасы `r0`, `r1`, ...
на `repository(owner, name) { pullRequests, defaultBranchRef { target { ...on
Commit { checkSuites } } } }`) на чанк до 50 репозиториев вместо 3 REST-ish
`gh`-вызовов на репозиторий — портфель из 9 репо теперь укладывается в один
`gh api graphql`. CI берётся из `checkSuites` HEAD-коммита дефолтной ветки
(агрегация по нескольким suite: FAILURE > PENDING > SUCCESS > UNKNOWN; suite
ровно в форме `status=QUEUED, conclusion=null, workflowRun=null` — живьём
пойманный на портфеле GitHub-плейсхолдер без реального CI-сигнала —
отбрасывается; фильтр намеренно **не** по одному лишь `workflowRun == null`,
т.к. это поле null-по-схеме и для настоящих non-Actions check suite'ов
(сторонний CI через Checks API), которые должны учитываться). Частичный сбой
(репо не резолвится) деградирует только этот репозиторий через
`errors[].path`; сбой всего вызова (auth/timeout/gh недоступен, либо
неразбираемый ответ) для чанка из нескольких репо **не** деградирует его
целиком — каждый репозиторий чанка повторно запрашивается отдельным
одиночным GraphQL-вызовом, изоляция per-repo восстанавливается на пути
ретрая (см. `fetchStatusesBatch`). `GitHubClient.fetchStatuses` получил
default-реализацию (конкурентный fan-out по `fetchStatus`), так что фейки в
тестах не менялись. Строки ошибок больше не содержат префикс `gh api
graphql:` — иначе `GhDiagnostics.looksLikeMissingGh()` ложно триггерился на
"gh"+"not found" в сообщении о несуществующем репозитории. См.
`GhCliClient.buildQuery`/`parseGraphQlResponse` (public для юнит-тестов без
реального `gh`), `GhCliClientGraphQlTest`, `GhDiagnosticsTest`.

## 3. Кэш статусов — СДЕЛАНО (история/тренды — нет, см. ниже)
`StatusCache` (`cache/StatusCache.kt`) — JSON-файл рядом с конфигом
(`status-cache.json`), последний успешный `RepoStatus` на репозиторий (не
временной ряд). `DashboardService.portfolioStatus()` мержит свежий опрос с
кэшем через чистую функцию `mergeWithCache`:

- Живой сбой + есть кэш → показывается закэшированный снимок, помечен
  `stale = true`; живая ошибка сохраняется в `RepoStatus.error` (не теряется) и
  видна и в CLI (`[cached: <текст ошибки>]`), и в веб-UI (title-тултип на бейдже).
- Живой успех + CI отличается от закэшированного → `ciChanged = true`, метка
  `[changed]` / бейдж в UI.
- Запись атомарная (temp-файл + `ATOMIC_MOVE`) — конкурентные вызовы
  `portfolioStatus()` (например, диагностический поток при старте веб-режима и
  первый запрос `/api/status`) не портят файл.
- `GhDiagnostics.hint()` не считает `stale`-репозитории «недоступными» — иначе
  штатная деградация в кэш ложно диагностировалась бы как «gh не работает».

Полная история/тренды (несколько точек времени на репозиторий) — сознательно
**не** реализовано: кэш хранит только последнее значение, это отдельная
будущая задача (см. план `docs/plans/post-mvp-status-cache.md`).

## 4. Health-чеки сервисов проектов
Для репозиториев, у которых есть задеплоенный сервис (напр. `aiTelegaBot`,
`aiMCPGate`), пинговать их health-эндпоинты и показывать up/down рядом с CI.
Список эндпоинтов — в конфиг (`health_url` на репозиторий).

## 5. Push-уведомления о падении CI
Опциональный watch-режим: периодический опрос + уведомление (Telegram-бот автора
`aiTelegaBot` или desktop-notify) при переходе CI success→failure.

## 6. Always-on режим на VPS (если реально понадобится)
Только при доказанной потребности. Появляется Dockerfile + compose, bind не на
loopback, аутентификация (single-user token), HTTPS через reverse-proxy. VPS ~$5/мес
укладывается в бюджет $50–70/мес. TODO-агрегатор в этом режиме потребует доступа к
рабочим копиям (git-fetch на сервере или отказ от Фазы 2 в облаке).

## 7. Улучшения TODO-агрегатора
- Учитывать `git blame` для «возраст» TODO (старые = выше приоритет либо помечены как гниль).
- Ссылки прямо в GitHub (`github.com/owner/repo/blob/branch/file#Lline`).
- Дедуп идентичных TODO, скопированных между репозиториями.
- Игнор-паттерны в конфиге (не только каталоги, но и glob по файлам).

## 8. Метрики портфеля — СДЕЛАНО
Мини-дашборд «здоровья портфеля» (4 карточки над табами в веб-UI): суммарно
открытых PR, репозиториев с красным CI, самый «долгий» открытый PR (репо,
номер, заголовок, дата, ссылка на GitHub), число TODO по важности.

Три метрики из четырёх (сумма `openPrs`, счётчик `ci == FAILURE`, разбивка
TODO по `priority`) derivable на клиенте из данных, которые `index.html` и
так уже загружает через `/api/status`+`/api/todos` — отдельный backend-эндпоинт
`/api/summary` сознательно не заводился (см. `docs/plans/post-mvp-portfolio-metrics.md`).
Единственная метрика, потребовавшая нового поля с backend, — «самый долгий
открытый PR»: `RepoStatus` получил nullable `oldestOpenPr: PrInfo?` (дефолт
`null`, обратно совместим со старыми `status-cache.json`), а GraphQL-запрос
в `GhCliClient.buildQuery` довозвращает старейший открытый PR каждого
репозитория (`pullRequests(states: OPEN, first: 1, orderBy: {field:
CREATED_AT, direction: ASC}) { totalCount nodes { number title createdAt
url } }`) за тот же round-trip, без доп. постраничной выборки. См.
`github/Models.kt` (`PrInfo`), `github/GhCliClient.kt` (`buildQuery`/
`parseRepoNode`/`toPrInfo`), `static/index.html` (`renderSummary`).
