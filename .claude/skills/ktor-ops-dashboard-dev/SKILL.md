---
name: ktor-ops-dashboard-dev
description: Конвенции и технические решения разработки aiProjectDashboard — чистый Ktor-бэкенд (CIO-движок, роутинг, content-negotiation, корутины), доступ к GitHub REST/GraphQL через gh CLI с переиспользованием gh auth, паттерн сканирования файловой системы для кросс-репо TODO/FIXME-агрегатора, порты и адаптеры для GitHubClient/TodoRanker, offline-эвристический ранкер. Использовать при реализации любого этапа кодирования aiProjectDashboard.
---

# SKILL: ktor-ops-dashboard-dev — конвенции проекта `aiProjectDashboard`

Конкретные конвенции **именно этого проекта** для написания Kotlin-кода. Это не
общий гайд «как писать на Ktor», а специфика `aiProjectDashboard`. Применяй при
реализации любого этапа. Опорные документы:
[`../../../docs/TECHNICAL_PLAN.md`](../../../docs/TECHNICAL_PLAN.md),
[`../../../docs/PLAN.md`](../../../docs/PLAN.md).

---

## 1. Структура Ktor-приложения

- `Main.kt` — **тонкий** entrypoint: разбор аргументов, загрузка `AppConfig`,
  сборка `DashboardService` из адаптеров, выбор режима (web / `--cli`). Никакой
  бизнес-логики.
- `DashboardService` — ядро. Зависит **только от портов** (`GitHubClient`,
  `TodoRanker`), не от их реализаций. Одинаково используется из веб-маршрутов и
  из CLI. Всё, что делает приложение полезным, живёт здесь и в пакетах-адаптерах.
- `http/Server.kt` — конфигурация Ktor: `embeddedServer(CIO, host="127.0.0.1", ...)`,
  установка плагинов (`ContentNegotiation` с kotlinx-json, `CallLogging`,
  `StatusPages`), маршруты. Функция `dashboardServer(...)` **возвращает** сервер,
  но не стартует его — старт в `Main.kt` (упрощает тесты через `testApplication`).
- По пакету на ответственность: `config/`, `github/`, `scan/`, `rank/`, `http/`, `cli/`.

**Движок — CIO** (pure-Kotlin, без Netty/нативных зависимостей). Не менять на Netty
без причины: CIO проще для локального loopback-инструмента.

**Bind только на `127.0.0.1`.** Это личный инструмент; никогда не слушать `0.0.0.0`.
Отсюда: не нужен HTTPS, auth, CORS, rate-limiting — не добавляй их «на всякий случай».

## 2. Доступ к GitHub — через `gh` CLI (MVP)

Решение зафиксировано (`TECHNICAL_PLAN.md §1–2`). Причина — **не хранить токен**:
переиспользуем существующую `gh auth`-сессию пользователя.

- Все вызовы `gh` спрятаны за портом `GitHubClient` (`fetchStatus(repo)`).
  Реализация — `GhCliClient`. Если позже понадобится прямой REST/GraphQL через
  Ktor Client — добавляется новый адаптер, `DashboardService` и маршруты не трогаются.
- Запуск `gh` — через `ProcessBuilder` в `Dispatchers.IO`, с **таймаутом**
  (`proc.waitFor(timeout, SECONDS)`, иначе `destroyForcibly()`). Читать stdout и
  stderr раздельно, проверять `exitValue()`, оборачивать ошибку текстом stderr.
- Парсить JSON-вывод `gh` (`gh ... --json ...`, `gh api ...`) через
  kotlinx.serialization (`Json { ignoreUnknownKeys = true }`), работать с
  `JsonElement`/`jsonObject`/`jsonArray` там, где схема мелкая.
- **Graceful degradation:** любую ошибку по репозиторию складывать в
  `RepoStatus.error`, а не бросать наверх — одна упавшая репа не должна ронять
  весь дашборд. `fetchStatus` ловит `Exception` и возвращает degraded `RepoStatus`.
- Нормализовать вокабуляр GitHub в свои enum'ы (`CiStatus`) в одном месте
  (`GhCliClient.mapCi`) — это тестируется юнит-тестом без сети.

### GraphQL (post-MVP)
Когда портфель разрастётся — один GraphQL-запрос вместо N REST-вызовов на репу
(`gh api graphql -f query=...`). Спрятать за тем же портом `GitHubClient`.

## 3. Конкурентность

- Опрос репозиториев Фазы 1 — **конкурентно**: `coroutineScope { repos.map { async { ... } }.awaitAll() }`.
  Уже реализовано в `DashboardService.portfolioStatus()`.
- Блокирующий I/O (`ProcessBuilder`, чтение файлов) выполнять на `Dispatchers.IO`
  (`withContext(Dispatchers.IO) { ... }`), не блокировать event-loop Ktor.

## 4. Сканер файловой системы (TODO-агрегатор)

Паттерн в `scan/TodoScanner.kt`:

- Разворачивать `~` в `localPath` через `expandHome` (`config/Paths.kt`).
- Обход — `Files.walk(root).use { ... }` (обязательно `.use` — стрим держит
  файловые дескрипторы). Фильтры: обычный файл → не в excluded-каталоге →
  расширение из `scanExtensions`.
- Исключения каталогов проверять по **относительному** пути (каждый сегмент
  против `scanExcludeDirs`: `.git`, `node_modules`, `build`, `target`, `.venv`, …).
- Маркеры — один regex, собранный из `config.markers` с `Regex.escape` и
  границей слова, чтобы `TODOLIST` не матчился как `TODO`. Захватывать текст
  после `маркер[:\-]?`.
- Чтение файла оборачивать в `runCatching` — бинарные/недоступные файлы не
  должны ронять скан.
- Сканер **чистый**: только ФС + regex, без git и без сети. Значит —
  детерминированно тестируется против `Files.createTempDirectory(...)`.

## 5. Ранжирование (порты и offline-режим)

- `TodoRanker` — порт (`suspend fun rank(items): List<TodoItem>`).
- `HeuristicRanker` — offline-реализация по умолчанию (скоринг по маркеру +
  urgency-словам), **без сети и ключа**. Это портфельная конвенция offline-mode:
  приложение полностью работоспособно без LLM-ключа.
- `LlmRanker` (post-MVP) — через Ktor Client к OpenAI-совместимому API. Ключ —
  **только из переменной окружения**, никогда из конфига/репозитория. Нет ключа →
  тихий откат на `HeuristicRanker`.

## 6. Конфиг

- `AppConfig`/`RepoConfig` — `@Serializable`, snake_case через `@SerialName`.
- Отсутствие файла → `AppConfig.default()` (встроенный портфель). Портфель
  **никогда не хардкодить** в логике — только как default-seed в конфиге.
- `Json { ignoreUnknownKeys = true }` — чтобы старый конфиг не ломался при
  добавлении полей.
- `encodeToString` требует явного сериализатора: `json.encodeToString(serializer(), value)`
  (без него компилятор Kotlin 2.0 не выведет тип — это реальная грабля, уже
  напоровшаяся при bootstrap).

## 7. Тестирование

Пирамида с интеграционным ярусом, не только мок-HTTP-юниты:

- **Юнит:** `TodoScanner` против temp-каталога (маркеры, исключения, не-маркеры);
  `HeuristicRanker` (порядок приоритетов); `AppConfig` JSON round-trip;
  `GhCliClient.mapCi`.
- **Маршруты (Этап 1+):** `ktor-server-test-host` (`testApplication { ... }`) с
  **фейковым** `GitHubClient`, гоняющим реальный стек Ktor — проверять
  `/api/status`, `/api/todos`, degraded-путь и JSON-контракт. Именно фейк, а не
  мок HTTP-клиента: тест должен прогонять роутинг/сериализацию по-настоящему.
- `suspend`-функции тестировать через `kotlinx-coroutines-test` (`runTest`).
- Перед коммитом — зелёный `./gradlew build`.

## 8. Грабли Gradle

- `org.gradle.configuration-cache=true` оставлен **выключенным** в
  `gradle.properties` (закомментирован с причиной) — ложные ошибки сериализации
  на некоторых связках (наблюдалось в `orm-nplus1-radar`). Не включать без
  проверки `./gradlew build --configuration-cache`.
- При проверке кода возврата сборки не использовать `| tail` перед `$?` —
  проверять `${PIPESTATUS[0]}`.
