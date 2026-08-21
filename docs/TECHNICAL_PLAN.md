# TECHNICAL_PLAN — стек, архитектура, этапы `aiProjectDashboard`

> Продолжение [`PLAN.md`](./PLAN.md). Здесь — технический выбор, архитектура и
> разбивка по этапам («Этап N», Этап 0 = bootstrap).

---

## 1. Стек

| Слой | Выбор | Причина |
|---|---|---|
| Язык | Kotlin (JVM, toolchain 17) | целевая учебная «мышца»; JDK 17 и 21 стоят через SDKMAN |
| Сборка | Gradle (Kotlin DSL) + wrapper 8.10.2 | wrapper фиксирует версию для воспроизводимости |
| HTTP-сервер | **Ktor 2.3.x, движок CIO** | чистый Kotlin-бэкенд без сервлет-контейнера; CIO — pure-Kotlin, без нативных зависимостей |
| HTTP-клиент | Ktor Client (CIO) | тот же стек для запросов к GitHub REST/GraphQL (post-MVP) |
| Сериализация | kotlinx.serialization (JSON) | конфиг + API-пейлоады; без рефлексии |
| Конкурентность | kotlinx.coroutines | параллельный опрос репозиториев |
| Доступ к GitHub (MVP) | **`gh` CLI через `ProcessBuilder`** | переиспользует `gh auth`; приложение не хранит токен |
| Логирование | logback-classic | стандарт для Ktor |
| Тесты | kotlin.test + JUnit Platform + ktor-server-test-host | юнит + маршруты |
| UI | статический `index.html` + vanilla JS | нулевая фронтенд-инфраструктура; отдаётся из ресурсов |

## 2. Архитектурное решение: локальный запуск vs постоянный веб-сервис

**Решение: локальный on-demand Ktor-сервер, слушающий только `127.0.0.1`.**
Веб-дашборд открывается в браузере (`http://127.0.0.1:8087`), плюс есть
headless CLI-режим (`--cli status|todos`). VPS **не разворачивается**.

Обоснование:

- **Данные локальны.** TODO-агрегатор физически читает рабочие копии на диске
  автора. Сервис на VPS не имел бы к ним доступа без синхронизации — лишняя
  сложность ради нулевой выгоды для соло-пользователя.
- **Безопасность.** Loopback-only убирает целый класс рисков: не нужен HTTPS,
  auth, rate-limiting, защита от чужого трафика. Токен GitHub не покидает
  машину и не хранится приложением (переиспользуем `gh auth`).
- **Стоимость.** $0/мес против ~$5/мес VPS. Для инструмента, который автор
  запускает эпизодически, always-on-сервис — трата и бюджета, и внимания на
  обслуживание.
- **«Мышца Kotlin» сохраняется полностью.** Ktor-сервер, роутинг,
  content-negotiation, корутины, HTTP-клиент, ProcessBuilder — учебная нагрузка
  та же, что у «настоящего» сервиса; отличается только bind-адрес и модель запуска.

Почему всё-таки **веб**, а не чистый CLI/TUI: таблицы CI-статусов и списки TODO
удобнее читать в браузере (цвет, сортировка, вкладки), а Ktor — как раз та
технология, которую хочется потренировать. CLI-режим оставлен как дешёвый
headless-выход и для быстрых проверок из терминала.

### Docker Compose — НЕ добавляется

Обоснованно опущен. Это локальный on-demand инструмент без внешних сервисов
(нет БД, брокера, кэша): оркестровать нечего. Единственная внешняя зависимость —
`gh` CLA на хосте, который в контейнер оборачивать бессмысленно (ему нужна
хостовая `gh auth`-сессия и доступ к рабочим копиям на диске хоста). Запуск —
`./gradlew run` или собранный дистрибутив. Если в post-MVP появится always-on
режим на VPS — тогда и появится Dockerfile (см. `POST_MVP_PLAN.md`).

## 3. Архитектура (порты и адаптеры)

```
src/main/kotlin/dev/akomyagin/dashboard/
├── Main.kt                 # entrypoint: разбор аргументов, выбор режима (web/cli)
├── DashboardService.kt     # ядро: оркестрирует Фазу 1 (status) и Фазу 2 (todos)
├── config/
│   ├── Config.kt           # AppConfig/RepoConfig + загрузка JSON + default-портфель
│   └── Paths.kt            # разворачивание ~ в домашний каталог
├── github/
│   ├── Models.kt           # CiStatus, CommitInfo, RepoStatus
│   ├── GitHubClient.kt     # ПОРТ: fetchStatus(repo) + fetchStatuses(repos) (batch, default — конкурентный fan-out)
│   └── GhCliClient.kt      # АДАПТЕР: `gh` CLI; fetchStatuses — один `gh api graphql` на чанк репозиториев
├── scan/
│   └── TodoScanner.kt      # обход ФС, regex-маркеры → List<TodoItem>
├── rank/
│   └── TodoRanker.kt       # ПОРТ TodoRanker + АДАПТЕР HeuristicRanker (offline)
├── http/
│   └── Server.kt           # Ktor: плагины + маршруты (/api/status, /api/todos, статика)
└── cli/
    └── Cli.kt              # рендер тех же данных в терминал
src/main/resources/
├── static/index.html       # SPA: две вкладки, fetch к /api/*
└── logback.xml
```

`DashboardService` зависит **только от портов** (`GitHubClient`, `TodoRanker`),
поэтому одинаково используется из веб-маршрутов и из CLI, и легко тестируется
с фейками.

## 4. Модель данных (сериализуемые DTO)

- `RepoConfig(name, owner, localPath, branch)` — элемент портфеля.
- `AppConfig(repos, markers, scanExtensions, scanExcludeDirs, port)` — весь
  конфиг; `AppConfig.default()` даёт встроенный портфель для запуска без файла.
- `RepoStatus(name, fullName, ci, ciDetail, lastCommit, openPrs, error)` —
  результат Фазы 1; `error != null` = degraded по этому репозиторию.
- `TodoItem(repo, marker, text, file, line, priority, reason)` — результат Фазы 2.

## 5. Этапы реализации

### Этап 0 — bootstrap (СДЕЛАНО в этом коммите-скелете)
Gradle-проект (`build.gradle.kts`, wrapper 8.10.2), пакетная структура,
рабочий Ktor-сервер + CLI-заглушки, конфиг с default-портфелем, статический UI,
базовые тесты (scanner, ranker, config round-trip, CI-mapping). `./gradlew build`
проходит.

### Этап 1 — Фаза 1: агрегация статуса портфеля (СДЕЛАНО)
- `GhCliClient` — боевой адаптер: CI-статус, последний коммит, число открытых PR;
  таймаут на `gh`, разбор ошибок в `RepoStatus.error` (graceful degradation).
- Веб-вкладка «Portfolio status» + `--cli status`.
- Тесты: фейковый `GitHubClient` (`PortfolioStatusTest`), degraded-путь, маппинг
  CI-статусов (`GhCliClient.mapCi`), маршрут `/api/status` (`StatusRouteTest`).
- **Изменено после `POST_MVP_PLAN.md` §2 (GraphQL-батчинг):** CI больше не берётся
  из «последнего Actions-run» через `gh run list`, а агрегируется из `checkSuites`
  HEAD-коммита дефолтной ветки. Конкурентный опрос переехал из `DashboardService`
  в порт `GitHubClient.fetchStatuses` (default-реализация — fan-out по
  `fetchStatus`; `GhCliClient` переопределяет её батчем `gh api graphql`).
  `DashboardService` теперь только делегирует — как именно батчится, знает адаптер.

### Этап 2 — Фаза 2: кросс-репо TODO-агрегатор (СДЕЛАНО)
- `TodoScanner`: обход ФС, исключения каталогов, regex-маркеры, засчитываемые
  только в контексте комментария (не в строковых литералах и прозе докстрингов).
- `HeuristicRanker` как offline-ранкер; после Этапа 4 он стал fallback'ом
  `LlmRanker`, а не самостоятельным дефолтом в `Main`.
- Веб-вкладка «TODOs» + `--cli todos`.
- Тесты: temp-репозиторий, исключения каталогов, не-маркеры, `.py`/`.php`
  комментарии (`TodoScannerTest`), скоринг, маршрут `/api/todos` (`StatusRouteTest`).

### Этап 3 — конфиг и UX (СДЕЛАНО)
- Команда/поток инициализации конфига: `--init-config` генерирует стартовый
  `~/.config/aiProjectDashboard/config.json` из `AppConfig.default()` (создавая
  каталоги), печатает путь + подсказку и выходит. Существующий файл не
  перезаписывается без `--force` (данные пользователя сохраняются).
  Логика — чистая функция `initConfig()` (`config/ConfigInit.kt`).
- Аккуратный вывод ошибок: `GhDiagnostics.hint()` — если ВЕСЬ портфель
  degraded по причине «нет `gh` / нет `gh auth`», печатается ОДНА дружелюбная
  подсказка в stderr (а не N одинаковых строк). Частичные сбои остаются на
  per-repo рендере. Отсутствие локальной копии для скана — тихий skip
  (задокументировано в `TodoScanner.scanRepo`).
- Опциональное авто-открытие браузера: флаг `--open` в web-режиме
  (`BrowserOpener` — `Desktop.browse` с fallback на `xdg-open`/`open`/`start`);
  best-effort, никогда не роняет сервер на headless. Не поведение по умолчанию.

### Этап 4 — реальный LLM-ранкер (СДЕЛАНО)
- Адаптер `LlmRanker` (`rank/LlmRanker.kt`) — Ktor Client к OpenAI-совместимому
  `/chat/completions` (один батч-запрос на весь список TODO, структурированный
  JSON-ответ `priority 1..5 + reason`). Ключ — только из переменной окружения
  `DASHBOARD_LLM_API_KEY` (плюс опциональные `DASHBOARD_LLM_BASE_URL`,
  `DASHBOARD_LLM_MODEL`), никогда из конфига/репозитория.
- Отсутствие ключа, сетевая ошибка или невалидный JSON от LLM — тихий откат на
  `HeuristicRanker` (никогда не роняет запрос и не показывает ошибку
  пользователю). `Main.kt` всегда конструирует `LlmRanker()` — переключение на
  оффлайн-режим происходит внутри самого ранкера, а не веткой в `Main`.
- Тесты (`LlmRankerTest`) — фейковый транспорт `ktor-client-mock`: нет ключа
  (без сетевого вызова), успешный разбор ответа, сетевая ошибка, невалидный
  JSON — все ветки падают на эвристику.

## 6. Тестирование

Пирамида включает интеграционный ярус, а не только мок-HTTP-юниты:
детерминированные фейки/temp-директории, гоняющие реальный стек.

- **Юнит:** `TodoScanner` против temp-каталога; `HeuristicRanker` скоринг;
  `AppConfig` JSON round-trip; `GhCliClient.mapCi` маппинг.
- **Маршруты (Этап 1+):** `ktor-server-test-host` — `/api/status`, `/api/todos`
  с фейковым `GitHubClient`, проверка degraded-пути и JSON-контракта.
- Команды перед коммитом: `./gradlew build` (компиляция + тесты + jar).
- **CI:** тот же `./gradlew build` гоняется в GitHub Actions
  (`.github/workflows/ci.yml`, JDK 17/temurin, `gradle/actions/setup-gradle`
  для кэша) на каждый push/PR в `master`. Мердж — только после зелёного чека
  (`gh pr checks`).

## 7. Известные грабли (из `orm-nplus1-radar`)

- `org.gradle.configuration-cache=true` в `gradle.properties` давал ложные ошибки
  сериализации на некоторых связках — здесь оставлен **выключенным** с
  комментарием-причиной. Не включать без проверки `./gradlew build --configuration-cache`.
- IntelliJ Platform Gradle plugin здесь **не используется** (это обычный Ktor,
  не IDE-плагин) — соответствующего класса проблем нет.
- При проверке результата сборки не использовать `| tail` перед `$?` —
  проверять `${PIPESTATUS[0]}`.
