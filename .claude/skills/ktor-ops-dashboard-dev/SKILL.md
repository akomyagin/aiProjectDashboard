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

- Все вызовы `gh` спрятаны за портом `GitHubClient` (`fetchStatus(repo)` — одиночный
  репозиторий, `fetchStatuses(repos)` — батч с default-реализацией через fan-out).
  Реализация — `GhCliClient`. Если позже понадобится другой адаптер (прямой REST
  через Ktor Client, другой CI-провайдер) — `DashboardService` и маршруты не трогаются.
- Запуск `gh` — через `ProcessBuilder` в `Dispatchers.IO`, с **таймаутом**
  (`proc.waitFor(timeout, SECONDS)`, иначе `destroyForcibly()`). Читать stdout и
  stderr раздельно, проверять `exitValue()`, оборачивать ошибку текстом stderr.
- Парсить JSON-вывод `gh` (`gh ... --json ...`, `gh api ...`) через
  kotlinx.serialization (`Json { ignoreUnknownKeys = true }`). Навигация по мелкой
  схеме — **только** через `as? JsonObject` / `as? JsonArray`: в ответах GraphQL
  JSON `null` приходит как отдельный `JsonElement`, и свойства `.jsonObject` /
  `.jsonArray` на нём **бросают** (напоролись на `defaultBranchRef: null`).
- **Graceful degradation:** любую ошибку по репозиторию складывать в
  `RepoStatus.error`, а не бросать наверх — одна упавшая репа не должна ронять
  весь дашборд. Ловит `fetchStatusesBatch` (`GhCliClient.kt:61`); `fetchStatus`
  теперь просто делегирует в `fetchStatuses(listOf(repo))`.
- Нормализовать вокабуляр GitHub в свои enum'ы (`CiStatus`) в одном месте
  (`GhCliClient.mapCi`) — это тестируется юнит-тестом без сети.

### GraphQL — СДЕЛАНО (post-MVP §2)
`GhCliClient.fetchStatuses` шлёт один `gh api graphql -f query=...` на чанк
репозиториев (алиасы `r0`, `r1`, ...); спрятано за портом `GitHubClient`.
Грабли, пойманные живьём (детали — `docs/POST_MVP_PLAN.md` §2):

- CI берётся из `checkSuites` HEAD-коммита дефолтной ветки, а не из `gh run list`.
- Плейсхолдер ровно в форме `status=QUEUED, conclusion=null, workflowRun=null`
  отбрасывается. Фильтровать **только** по `workflowRun == null` нельзя — это поле
  null-по-схеме и у настоящих non-Actions check suite'ов (сторонний CI через Checks API).
- Сбой батч-вызова не деградирует весь чанк: каждый репозиторий ретраится отдельным
  одиночным запросом, чтобы сохранить per-repo изоляцию.
- Строки ошибок не должны начинаться с `gh api graphql:` — иначе
  `GhDiagnostics.looksLikeMissingGh()` ложно срабатывает на «gh» + «not found».

## 3. Конкурентность

- Опрос репозиториев Фазы 1 — **конкурентно**, но решает это порт `GitHubClient`,
  а не ядро: `fetchStatuses(repos)` имеет default-реализацию
  `coroutineScope { repos.map { async { fetchStatus(it) } }.awaitAll() }`, а
  `GhCliClient` переопределяет её батчем (чанки идут конкурентно).
  `DashboardService.portfolioStatus()` только делегирует — **не возвращать fan-out
  туда обратно**.
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
- Маркеры — regex, собранный из `config.markers` с `Regex.escape` и границей
  слова, чтобы `TODOLIST` не матчился как `TODO`. Захватывать текст после
  `маркер[:\-]?`. Маркер засчитывается только в контексте комментария:
  непосредственно (не считая пробелов) после line-comment токена, уместного
  для типа файла (`//` — `.kt/.go/.ts/.tsx`, `#` — `.py`, `//` или `#` —
  `.php`), либо после открытия/продолжения блочного комментария (`/*` или
  `*` в начале строки). Так слова-маркеры в строковых литералах или в прозе
  докстрингов (например, «used by the TODO/FIXME scanner» в KDoc) не считаются
  реальными хитами.
- Чтение файла оборачивать в `runCatching` — бинарные/недоступные файлы не
  должны ронять скан.
- Сканер **чистый**: только ФС + regex, без git и без сети. Значит —
  детерминированно тестируется против `Files.createTempDirectory(...)`.

## 5. Ранжирование (порты и offline-режим)

- `TodoRanker` — порт (`suspend fun rank(items): List<TodoItem>`).
- `HeuristicRanker` — offline-реализация (скоринг по маркеру + urgency-словам),
  **без сети и ключа**. Это портфельная конвенция offline-mode: приложение полностью
  работоспособно без LLM-ключа. Внимание: `Main.kt` конструирует **не** его, а
  `LlmRanker()` — `HeuristicRanker` работает как fallback внутри него.
- `LlmRanker` — СДЕЛАНО (Этап 4), через Ktor Client к OpenAI-совместимому
  `/chat/completions`. Ключ **только из переменной окружения** `DASHBOARD_LLM_API_KEY`
  (плюс опциональные `DASHBOARD_LLM_BASE_URL`, `DASHBOARD_LLM_MODEL`), никогда из
  конфига/репозитория. Нет ключа, сетевая ошибка или невалидный JSON → тихий откат на
  `HeuristicRanker`. Инварианты, добытые ревью — не ломать:
  - **`HttpTimeout` обязателен** на клиенте. `runCatching` ловит исключения, но не
    зависание: без таймаута зависший эндпоинт вешает `/api/todos` и `--cli todos` навсегда.
  - Сбой **логировать** в stderr перед откатом, иначе «LLM молча не работает»
    неотличимо от «ключ не задан» — правило проекта: делать причину видимой
    (ср. `GhDiagnostics`).
  - `Main.kt` после `--cli` режимов зовёт `exitProcess(0)`: незакрытый `HttpClient`
    держит потоки движка и мешает процессу выйти.

## 6. Конфиг

- `AppConfig`/`RepoConfig` — `@Serializable`, snake_case через `@SerialName`.
- Отсутствие файла → `AppConfig.default()` (встроенный портфель). Портфель
  **никогда не хардкодить** в логике — только как default-seed в конфиге.
- `Json { ignoreUnknownKeys = true }` — чтобы старый конфиг не ломался при
  добавлении полей.
- `encodeToString` требует явного сериализатора для `@Serializable`-типов:
  `json.encodeToString(serializer(), value)` (без него компилятор Kotlin 2.0 не
  выведет тип — реальная грабля, уже напоровшаяся при bootstrap; см. `AppConfig.serialize`).
  Для примитивов (`String` и т.п.) хватает reified-формы `json.encodeToString(value)`
  с `import kotlinx.serialization.encodeToString` — так сделано в `GhCliClient.quote()`.

## 7. Тестирование

Пирамида с интеграционным ярусом, не только мок-HTTP-юниты:

- **Юнит:** `TodoScanner` против temp-каталога (маркеры, исключения, не-маркеры);
  `HeuristicRanker` (порядок приоритетов); `AppConfig` JSON round-trip;
  `GhCliClient.mapCi`/`buildQuery`/`parseGraphQlResponse` (последние два — public
  специально ради тестов без реального `gh`); `LlmRanker` — фейковый `HttpClient`
  (`ktor-client-mock`), нет ключа / успешный разбор / сетевая ошибка / невалидный JSON.
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
