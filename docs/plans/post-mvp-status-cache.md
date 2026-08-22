# План: локальный кэш и диффы статуса (POST_MVP_PLAN.md §3)

Ветка: `post-mvp/status-cache`. Реализует §3 из `docs/POST_MVP_PLAN.md`:
«Локальный кэш последнего успешного ответа: мгновенный первый рендер, работа
офлайн, диффы «CI упал с прошлого раза»». Полная история/тренды **не** входят
в этот этап — только последнее известное значение на репозиторий (сам §3 это
явно разделяет: кэш — «основа для трендов», не тренды).

## Цель

1. Если живой опрос репозитория деградировал (`RepoStatus.error != null`),
   но раньше был успешный ответ — показать последний известный успешный статус
   вместо голой ошибки, явно пометив его как несвежий.
2. Если живой опрос успешен и CI-статус отличается от последнего закэшированного
   — пометить это как изменение (диф), чтобы UI/CLI могли выделить «CI упал» /
   «CI починился».
3. Кэш переживает перезапуск процесса (файл на диске), не требует БД.

## Инварианты, которые нельзя ломать

- **Не трогать `GitHubClient`/`GhCliClient`** — кэш не имеет отношения к тому,
  как достаётся статус (REST/GraphQL/батч), он выше по слою. Не пытаться
  засовывать кэш внутрь `GhCliClient.fetchStatuses`.
- **Graceful degradation остаётся построчной**: сбой чтения/записи кэша (файл
  повреждён, нет прав, диск полон) никогда не должен ронять `/api/status` или
  CLI — деградирует тихо до «кэша нет», как будто это первый запуск.
- **Не заводить SQLite и вообще новую зависимость.** В проекте уже есть
  kotlinx.serialization для JSON — этого достаточно для ~9 записей. Не
  предлагать миграцию на БД в рамках этой задачи.
- **`DashboardService.portfolioStatus()` остаётся простой оркестрацией** —
  сначала спросить `GitHubClient.fetchStatuses`, потом прогнать через кэш.
  Конкурентность/батчинг там, где они уже есть (в `GitHubClient`), это не
  трогаем.
- Обратная совместимость JSON: новые поля `RepoStatus` — с дефолтами (`= false`
  / `= null`), чтобы `@Serializable`-контракт не ломал существующие тесты,
  которые строят `RepoStatus(...)` без этих полей.

## Дизайн

### Новый пакет `cache/`

`src/main/kotlin/dev/akomyagin/dashboard/cache/StatusCache.kt`:

```kotlin
package dev.akomyagin.dashboard.cache

import dev.akomyagin.dashboard.github.RepoStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

@Serializable
data class CachedEntry(val status: RepoStatus, val cachedAtEpochSeconds: Long)

/**
 * Last-known-good status per repo, keyed by RepoStatus.fullName, persisted as
 * one JSON file. Not a history/time-series — one entry per repo, always
 * overwritten on the next successful poll. Read/write failures degrade to "no
 * cache" silently; this is a convenience layer, never a source of truth that
 * can crash a poll.
 */
class StatusCache(private val path: Path) {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): Map<String, CachedEntry> = runCatching {
        if (!path.exists()) return emptyMap()
        json.decodeFromString<Map<String, CachedEntry>>(Files.readString(path))
    }.getOrElse { emptyMap() }

    fun save(entries: Map<String, CachedEntry>) {
        runCatching {
            path.parent?.let { Files.createDirectories(it) }
            Files.writeString(path, json.encodeToString(entries))
        }
    }
}
```

(Уточнить у компилятора: `json.encodeToString(entries)` для `Map<String,
CachedEntry>` требует reified-формы `import kotlinx.serialization.encodeToString`
— то же самое, на чём напоролись в `Config.kt`/`GhCliClient.quote()`. Использовать
её, а не забывать сериализатор.)

### Модель: `RepoStatus` — 2 новых поля с дефолтами

`github/Models.kt`:

```kotlin
@Serializable
data class RepoStatus(
    val name: String,
    val fullName: String,
    val ci: CiStatus = CiStatus.UNKNOWN,
    val ciDetail: String? = null,
    val lastCommit: CommitInfo? = null,
    val openPrs: Int = 0,
    val error: String? = null,
    val stale: Boolean = false,       // NEW: это последний УСПЕШНЫЙ ответ из кэша, не живой
    val ciChanged: Boolean = false,   // NEW: ci отличается от того, что было в прошлом опросе
)
```

`stale` и `ciChanged` не бывают true одновременно с точки зрения смысла:
`stale=true` — текущий живой опрос упал, показываем архив; `ciChanged=true` —
текущий живой опрос успешен и CI изменился с прошлого раза. Оба поля
выставляет только слой кэширования, `GitHubClient`/`GhCliClient` их не знает
и не трогает (у него в возвращаемых `RepoStatus` они всегда дефолтные).

### Точка интеграции — `DashboardService`

`DashboardService.kt`:

```kotlin
class DashboardService(
    private val config: AppConfig,
    private val github: GitHubClient,
    private val ranker: TodoRanker,
    private val scanner: TodoScanner = TodoScanner(config),
    private val cache: StatusCache? = null,
) {
    suspend fun portfolioStatus(): List<RepoStatus> {
        val fresh = github.fetchStatuses(config.repos)
        val cache = this.cache ?: return fresh
        return applyCache(fresh, cache)
    }
    ...
}
```

Логика `applyCache` (может жить как приватный метод `DashboardService` или как
чистая функция в `cache/StatusCache.kt` — выбрать то, что проще тестировать;
рекомендация — чистая функция `mergeWithCache(fresh: List<RepoStatus>, cached:
Map<String, CachedEntry>): Pair<List<RepoStatus>, Map<String, CachedEntry>>` в
`StatusCache.kt`, чтобы `DashboardService` не разрастался и логику можно было
юнит-тестировать без корутин/сети):

Для каждого `RepoStatus` в `fresh`, по ключу `fullName`:
- Если `error == null` (живой опрос успешен):
  - Сравнить `ci` с `cached[fullName]?.status?.ci`. Если оба присутствуют и
    отличаются — вернуть копию с `ciChanged = true`.
  - Записать этот статус (без `ciChanged`/`stale` — кэшируем «чистый» снимок)
    в новую версию кэша под этим ключом с текущим временем.
- Если `error != null` (живой опрос деградировал):
  - Если в `cached` есть запись — вернуть её `status` с `stale = true`,
    `error` из **живого** опроса не терять: положить оригинальную ошибку в
    `ciDetail` или отдельно решить — простой вариант: у `RepoStatus` уже есть
    `ciDetail: String?`, но перезаписывать его кэшированным значением
    нежелательно. **Решение**: сохранить оригинальную живую ошибку в самом
    `RepoStatus`, который возвращаем — то есть взять кэшированный `status`,
    скопировать его с `stale = true`, но **оставить `error` от живого опроса**
    (не `null`), чтобы `GhDiagnostics`/UI видели, что проблема реальна, а не
    воображаемая, просто под ней показывается последний известный снимок.
    Итого: `cachedEntry.status.copy(error = fresh.error, stale = true)`.
  - Если записи нет — вернуть как есть (обычная деградация, без кэша).
- В конце — сохранить обновлённую карту через `cache.save(...)`, но **только
  успешные** записи трогать (не кэшировать деградированные ответы поверх
  хорошего кэша).

Явно продумать порядок полей при `.copy` — `RepoStatus.error` при
`stale = true` должен остаться live-ошибкой, а не `null` из закэшированного
снимка, иначе теряется факт деградации (для `GhDiagnostics.hint()` это важно:
он матчит по `error`).

### Путь к файлу кэша

Без нового CLI-флага: класть рядом с конфигом.
В `Main.kt`, там где сейчас формируется `configPath`:

```kotlin
val cachePath = configPath.resolveSibling("status-cache.json")
```

`StatusCache(cachePath)` передаётся в `DashboardService`. В `--cli` режимах и
веб-режиме — одинаково (это не какая-то web-only фича).

### CLI (`Cli.kt`)

`renderStatus`: если `s.stale` — добавить суффикс к колонке коммита, например
`(закэшировано Nm назад)` — **но** без даты кэширования эту фразу не собрать,
значит `RepoStatus` тоже должен нести время последнего успеха, либо не
показывать возраст, а просто пометить `[cached]`. Проще и без лишних полей —
просто литеральная пометка `[cached]` в конце строки, без вычисления возраста
(вычисление возраста — не то, что явно просят §3; «мгновенный первый рендер» и
«работа офлайн» не требуют точного age). Если `s.ciChanged` — пометить `[changed]`.
Пример: badge остаётся как есть, а в конце строки статуса добавляется суффикс.
Не переусложнять форматирование таблицы ради этого — суффикс к полю commit
или отдельная короткая пометка после badge, на усмотрение реализующего, лишь
бы `renderStatus` оставался читаемым и тестируемым построчным сравнением.

### Веб (`static/index.html`)

Минимальная правка: если `r.stale` — добавить CSS-класс/пометку у строки
(например, приглушённый стиль + текст «cached» рядом с CI-бейджем); если
`r.ciChanged` — короткий значок/текст рядом с CI-бейджем (например «changed»).
Не делать историю/график — вне рамок этого этапа.

### `Main.kt`

Подключить `StatusCache` в оба места, где строится `DashboardService`
(веб-режим и `--cli`) — сейчас это одна точка (`val service = DashboardService(...)`
строится один раз до ветвления `--cli`/web), так что менять нужно только
конструкцию один раз.

## Тесты (обязательно)

- `StatusCacheTest` (новый файл): `load()` без файла → пустая карта; `save()` +
  `load()` — round-trip; повреждённый JSON в файле → `load()` возвращает
  пустую карту, не бросает.
- `mergeWithCache`-тесты (или как назовётся чистая функция): 
  - живой успех, кэша нет → как есть, кэш пополняется;
  - живой успех, CI совпадает с кэшем → `ciChanged = false`;
  - живой успех, CI отличается от кэша → `ciChanged = true`, кэш обновляется
    новым значением;
  - живой сбой, есть кэш → возвращается кэшированный `status` с `stale = true`
    и **живой** `error` (не `null` и не старая ошибка/её отсутствие);
  - живой сбой, кэша нет → как есть, `stale` остаётся `false`.
- `PortfolioStatusTest`: `FakeGitHubClient` не меняется (интерфейс не менялся),
  но нужен хотя бы один тест `DashboardService` с реальным `StatusCache`
  (temp-файл) на happy path — что кэш действительно подключается через
  конструктор и не ломает существующий контракт при `cache = null`
  (дефолтное значение параметра — все существующие вызовы `DashboardService(...)`
  без пятого аргумента должны продолжать работать и компилироваться).
- `StatusRouteTest`: не обязателен новый тест, но проверить, что существующие
  тесты `/api/status` не ломаются лишними полями в JSON (kotlinx.serialization
  с дефолтами не должен менять поведение существующих `assertEquals` на другие
  поля).

## Критерий готовности

- `./gradlew build` зелёный, новые тесты покрывают все ветки `mergeWithCache`.
- Ручная проверка: `./gradlew run --args="--cli status"` дважды подряд с
  разницей в состоянии (например, временно передать заведомо деградирующий
  `GitHubClient` во втором прогоне — или проще: убить сеть/`gh auth logout` —
  чтобы увидеть `[cached]` во втором выводе). Если это неудобно организовать
  живьём — временный `--cli status` с моком через тест-код не нужен, ручная
  проверка может ограничиться тем, что после первого успешного прогона
  появился файл `status-cache.json` рядом с конфигом с валидным JSON.
- Не трогать `GhCliClient`, `GitHubClient` интерфейс (кроме, если понадобится,
  но по дизайну выше — не требуется).
- Не добавлять новых внешних зависимостей в `build.gradle.kts`.

## Не входит в эту задачу

- Полная история/тренды (несколько точек времени на репозиторий) — это
  отдельный будущий пункт, §3 сам называет кэш лишь «основой» для него.
- TTL/устаревание кэша по времени, конфигурируемый путь кэша, флаг
  `--no-cache` — не просили, не добавлять.
