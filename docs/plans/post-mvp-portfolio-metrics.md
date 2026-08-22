# План: мини-дашборд «здоровья портфеля» (POST_MVP_PLAN.md §8)

Ветка: `post-mvp/portfolio-metrics`. Реализует §8 из `docs/POST_MVP_PLAN.md`:
«Метрики портфеля: сводка — суммарно открытых PR, репозиториев с красным CI,
самый «долгий» открытый PR, число TODO по важности. Мини-дашборд «здоровья
портфеля» сверху страницы».

## Цель и критерий готовности

Пользователь открывает веб-UI (`http://127.0.0.1:8087`) и **над табами**
(`Portfolio status` / `TODOs`) видит блок из 4 карточек/чисел, посчитанных по
уже загруженным данным:

1. Суммарно открытых PR по всему портфелю (сумма `RepoStatus.openPrs`).
2. Количество репозиториев с красным CI (`RepoStatus.ci == FAILURE`).
3. Самый «долгий» открытый PR портфеля — репозиторий, номер/заголовок PR и
   сколько он открыт (или дата открытия), с ссылкой на GitHub.
4. Число TODO по важности — короткая разбивка по `priority` (1..5), из
   `TodoItem`.

Блок виден сразу после загрузки страницы (дефолтный таб — `status`, оба
источника данных к этому моменту доступны/дозагружаются) и обновляется при
каждом `show('status')`/переключении вкладок наравне с остальным UI —
отдельного поллинга не заводим.

Критерий готовности: `./gradlew build` зелёный; новые юнит-тесты на парсинг
GraphQL и на обратную совместимость кэша проходят; ручная проверка в браузере
— см. раздел «Ручная проверка».

## Ключевое архитектурное решение (зафиксировано, не пересматривать без сильной причины)

**Нет нового backend-эндпоинта `/api/summary`.** Три из четырёх метрик уже
derivable на клиенте из данных, которые `index.html` и так загружает через
`/api/status` и `/api/todos`:

- сумма `openPrs` — редьюс по массиву из `/api/status`;
- счётчик `ci === 'FAILURE'` — фильтр того же массива;
- разбивка TODO по `priority` — группировка массива из `/api/todos`.

Единственная метрика, которая **требует нового поля с backend**, — «самый
долгий открытый PR»: нужны `createdAt`/`title`/`url`/`number` отдельного PR,
которых сейчас нет нигде (`RepoStatus.openPrs` — только `totalCount`). Значит:
минимально расширяем `RepoStatus` новым nullable-полем, GraphQL-запрос
довозвращает старейший открытый PR каждого репозитория за тот же
round-trip (без доп. постраничной выборки), а вся агрегация по портфелю —
на клиенте в JS. Заводить сервер-сайд агрегацию ради оставшихся трёх метрик,
которые и так тривиально считаются в браузере, — лишняя абстракция.

## 1. Модель данных

`src/main/kotlin/dev/akomyagin/dashboard/github/Models.kt`:

Новый сериализуемый класс `PrInfo` и новое nullable-поле в `RepoStatus`:

```kotlin
@Serializable
data class PrInfo(
    val number: Int,
    val title: String,
    val createdAt: String,
    val url: String,
)

@Serializable
data class RepoStatus(
    val name: String,
    val fullName: String,
    val ci: CiStatus = CiStatus.UNKNOWN,
    val ciDetail: String? = null,
    val lastCommit: CommitInfo? = null,
    val openPrs: Int = 0,
    val error: String? = null,
    val stale: Boolean = false,
    val ciChanged: Boolean = false,
    /** Oldest currently-open PR on this repo (by createdAt), or null if none open.
     *  New/degraded/no-open-PR responses omit it — default keeps old cached
     *  status-cache.json entries (written before this field existed) deserializable. */
    val oldestOpenPr: PrInfo? = null,
)
```

Поле **обязательно с дефолтом `null`** — это прямое повторение инварианта,
уже зафиксированного при добавлении `stale`/`ciChanged` (`docs/plans/post-mvp-status-cache.md`,
раздел «Инварианты, которые нельзя ломать» и SKILL.md §5): новые поля
`RepoStatus` не должны ломать десериализацию уже написанных на диск
`status-cache.json` без этого поля.

## 2. GraphQL-запрос

`src/main/kotlin/dev/akomyagin/dashboard/github/GhCliClient.kt`, `buildQuery`
(~строка 165): заменить

```
pullRequests(states: OPEN) { totalCount }
```

на

```
pullRequests(states: OPEN, first: 1, orderBy: {field: CREATED_AT, direction: ASC}) {
  totalCount
  nodes { number title createdAt url }
}
```

Один и тот же запрос по-прежнему даёт `totalCount` (для суммы открытых PR) и
теперь — самый старый (=самый долгоживущий) открытый PR через `nodes` (первый
и единственный элемент благодаря `first: 1` + сортировке по `createdAt ASC`).
Доп. полей на round-trip не требуется — это тот же вызов, что и раньше, только
шире один блок.

`parseRepoNode` (~строка 229): рядом с текущим

```kotlin
val openPrs = node["pullRequests"].obj()?.get("totalCount")?.jsonPrimitive?.intOrNull ?: 0
```

добавить извлечение первого узла из `nodes` (может отсутствовать — пустой
массив, если открытых PR нет):

```kotlin
val prNodes = node["pullRequests"].obj()?.get("nodes").arr()
val oldestOpenPr = prNodes?.firstOrNull()?.obj()?.let { pr ->
    PrInfo(
        number = pr["number"]?.jsonPrimitive?.intOrNull ?: return@let null,
        title = pr["title"]?.jsonPrimitive?.contentOrNull ?: "",
        createdAt = pr["createdAt"]?.jsonPrimitive?.contentOrNull ?: "",
        url = pr["url"]?.jsonPrimitive?.contentOrNull ?: "",
    )
}
```

(конкретную форму `let`/`run` выбрать по вкусу реализующего — важно
использовать существующие `.obj()`/`.arr()` расширения, а не бросающие
`.jsonObject`/`.jsonArray`, т.к. `nodes` в принципе может прийти пустым
массивом, а не `null`-полем, но защититься и от отсутствующего поля тоже).
Передать `oldestOpenPr` в оба места, где сейчас строится `RepoStatus` внутри
`parseRepoNode` (happy path с `lastCommit`, и путь `defaultBranchRef == null`
"no commits" — репозиторий без коммитов на дефолтной ветке всё ещё может
иметь открытые PR, значит `openPrs`/`oldestOpenPr` нужно прокинуть и в этот
early-return, не только в основной).

Ветки, где `RepoStatus` строится с `error != null` (deg raded repo,
`parseGraphQlResponse`), `oldestOpenPr` не трогают — остаётся дефолтный `null`.

## 3. TODO по важности — без изменений backend

`DashboardService.todos()` уже возвращает `List<TodoItem>` с `priority: Int`
(`src/main/kotlin/dev/akomyagin/dashboard/scan/TodoScanner.kt:15`). Никаких
правок здесь не требуется — агрегация (`group by priority`) целиком в JS.

## 4. Веб-UI — новый блок сводки

`src/main/resources/static/index.html`:

- Новый `<div id="summary">` в `<main>`, **до** `<div id="view">` (то есть
  визуально над табами — таб-навигация уже в `<header>`, `#summary` идёт
  первым внутри `<main>`, перед текущим содержимым таба).
- Новая JS-функция, например `renderSummary(statusRows, todoRows)`, которая:
  - считает `totalOpenPrs = statusRows.reduce((a,r) => a + r.openPrs, 0)`;
  - считает `redCi = statusRows.filter(r => r.ci === 'FAILURE').length`;
  - находит самый старый PR: `statusRows.filter(r => r.oldestOpenPr).map(r => ({repo:r.fullName, ...r.oldestOpenPr})).sort((a,b) => a.createdAt.localeCompare(b.createdAt))[0]` —
    (ISO-8601 строки от GitHub лексикографически сравнимы, доп. Date-парсинг
    не нужен) — рендерит `repo#number title` со ссылкой `<a href="url">` и
    датой создания;
  - считает разбивку TODO по приоритету: `todoRows.reduce((m,t) => (m[t.priority]=(m[t.priority]||0)+1, m), {})`,
    рендерит компактно, например `P5:2 P4:1 P3:5 …` только для присутствующих
    приоритетов (переиспользовать существующие CSS-классы `.prio.p1`..`.p5`
    из текущей таблицы TODO, `:root`-стили менять не нужно).
- Источники данных: `renderSummary` вызывается из `show()` после того, как
  оба `/api/status` и `/api/todos` загружены. Так как сейчас `show(tab)`
  фетчит только данные активного таба, простейший вариант без лишней
  архитектуры — фетчить **оба** эндпоинта при каждом вызове `show()` (портфель
  маленький, ~9 репозиториев, дополнительный `/api/todos`/`/api/status` запрос
  не заметен на localhost) и рендерить сводку из обоих результатов, а
  таб-специфичную таблицу — как раньше, из своего результата. Не заводить
  глобальное состояние/кэш в JS ради экономии одного лишнего fetch — это
  лишняя сложность для 80-строчного файла без сборки.
- Пустые состояния: если `statusRows` пуст или ни у одного репозитория нет
  `oldestOpenPr` — секция «самый долгий PR» показывает `—`/`нет открытых PR`,
  не бросает и не рендерит `undefined`.
- Экранирование: как и остальной UI, использовать существующую `esc()` для
  всех значений, которые могут содержать пользовательский текст (`title`,
  `repo`).

Конкретную вёрстку (карточки в ряд / инлайн-строка) на усмотрение
реализующего — задача просит «мини-дашборд сверху», не диктует пиксельный
дизайн; выдержать существующую тёмную палитру (`#12141a`/`#8a94a6`/акценты
`#3ddc84`/`#ff5c5c`/`#ffcc00`, см. текущий `<style>`), новых цветов не
изобретать без необходимости.

## 5. Список изменяемых файлов

| Файл | Правка |
|---|---|
| `src/main/kotlin/dev/akomyagin/dashboard/github/Models.kt` | Новый `@Serializable data class PrInfo(number, title, createdAt, url)`; новое поле `RepoStatus.oldestOpenPr: PrInfo? = null`. |
| `src/main/kotlin/dev/akomyagin/dashboard/github/GhCliClient.kt` | `buildQuery`: расширить `pullRequests(...)` полями `first: 1, orderBy: {...}` и `nodes { number title createdAt url }`. `parseRepoNode`: извлечь `oldestOpenPr` из `nodes[0]`, прокинуть в оба места построения `RepoStatus` (happy path и "no commits" early-return). |
| `src/test/kotlin/dev/akomyagin/dashboard/GhCliClientGraphQlTest.kt` | Новые тест-кейсы (см. раздел 6) + обновить существующие фикстуры, если нужно (см. ниже — существующие тесты используют старую форму `pullRequests` без `nodes`, парсинг должен остаться совместимым с их отсутствием). |
| `src/test/kotlin/dev/akomyagin/dashboard/PortfolioStatusTest.kt` | `FakeGitHubClient.ok(...)` — опциональный параметр `oldestOpenPr: PrInfo? = null`, чтобы тесты сводки (если будут на уровне `DashboardService`) могли задавать его; иначе можно не трогать, если новых тест-кейсов на этом уровне не заводим (см. раздел 6 — агрегация сводки не тестируется на уровне Kotlin, она в JS). |
| `src/test/kotlin/dev/akomyagin/dashboard/cache/StatusCacheTest.kt` | Новый тест на обратную совместимость: JSON без поля `oldestOpenPr` в записи десериализуется, `oldestOpenPr == null`. |
| `src/main/resources/static/index.html` | Новый блок `#summary` над табами; JS-функция агрегации сводки; `show()` дозагружает оба источника. |

## 6. Тест-кейсы

### `GhCliClientGraphQlTest` (парсинг GraphQL)

- `buildQuery` содержит `orderBy: {field: CREATED_AT, direction: ASC}` и
  `first: 1` и `nodes { number title createdAt url }` в блоке `pullRequests`.
- Репозиторий с одним открытым PR в `nodes` → `oldestOpenPr` заполнен всеми
  4 полями корректно (номер, заголовок, дата, url).
- Репозиторий без открытых PR (`"nodes":[]`, `"totalCount":0`) →
  `oldestOpenPr == null`, `openPrs == 0`.
- Репозиторий, где `pullRequests` вообще не содержит `nodes` (старая форма
  ответа/фикстура без этого поля, как в уже существующих тестах, у которых
  тело `{"totalCount":N}` без `nodes`) → парсинг не падает, `oldestOpenPr == null`
  (защита через `.obj()?.get("nodes").arr()` — nullable-safe навигация,
  не бросающая на отсутствующем поле). Это же неявно проверяет, что все
  существующие тесты в файле (которые используют старую форму
  `pullRequests: {totalCount: N}`) продолжают проходить без изменений.
  Внутри early-return "no commits" (`defaultBranchRef: null`) — тоже
  проверить, что `oldestOpenPr` учитывается (репозиторий без коммитов, но
  с открытым PR).
- Несколько открытых PR в `nodes` (на случай, если тест захочет
  проверить, что берётся именно `nodes[0]`, а не последний) — можно
  ограничиться одним PR в `nodes`, т.к. `first: 1` на уровне GraphQL и так
  гарантирует не более одного элемента; per-репо агрегация "самый старый
  среди нескольких" не требуется на этом уровне (её делает сам сервер через
  `orderBy`).

### `StatusCacheTest` (обратная совместимость)

- Написать в temp-файл JSON-карту `{"akomyagin/shelf": {...RepoStatus без поля
  oldestOpenPr...}}` (форма, которую производил код до этой задачи) и
  проверить, что `StatusCache(path).load()` не бросает и возвращает запись с
  `oldestOpenPr == null`.
- `save()` + `load()` round-trip с записью, у которой `oldestOpenPr` заполнен
  — поле переживает сериализацию.

### На уровне `DashboardService`/`PortfolioStatusTest`

Новых тест-кейсов на уровне `DashboardService` не требуется — сервис не
меняется (см. раздел «Что НЕ трогать»), поле просто протекает через
существующий путь `GitHubClient → RepoStatus → (опционально) StatusCache`.
Если реализующий добавит `oldestOpenPr` в `FakeGitHubClient.ok(...)` ради
удобства — не обязательно, но не запрещено, если понадобится для конкретного
теста.

### Ручная/визуальная проверка (не автотест)

`./gradlew run`, открыть `http://127.0.0.1:8087`, убедиться, что над табами
виден блок с 4 числами/карточками, значения совпадают с ручным подсчётом по
`/api/status` и `/api/todos` (например, через `curl localhost:8087/api/status | jq`).
JS не тестируется юнит-тестами (в проекте нет фронтенд-тестового рантайма и
задача не просит его заводить) — только ручная проверка в браузере плюс
консоль без ошибок.

## 7. Что НЕ трогать и почему

- **`DashboardService`** — не добавлять новый метод/эндпоинт `summary()`.
  Вся сводка, кроме "самый долгий PR", derivable на клиенте из уже
  существующих `/api/status`+`/api/todos`; заводить сервер-сайд агрегацию
  ради этого — лишняя абстракция (см. архитектурное решение выше).
- **`http/Server.kt` роуты** — не добавлять `/api/summary`. Те же причины.
- **`cli/Cli.kt`** — §8 говорит про «мини-дашборд сверху страницы», это про
  веб-UI. `--cli status`/`--cli todos` не трогаем: агрегация метрик портфеля
  для headless-режима не запрошена явно и не является тривиальным довеском
  (это отдельный UX-вопрос — нужен ли summary-режим CLI, вне рамок §8).
- **`GitHubClient` интерфейс** — сигнатуры `fetchStatus`/`fetchStatuses` не
  меняются, расширяется только форма возвращаемого `RepoStatus`.
- **`StatusCache`/`mergeWithCache`** — логика мерджа кэша не меняется,
  `oldestOpenPr` просто ещё одно поле `RepoStatus`, которое кэш уже умеет
  хранить целиком (кэш сериализует весь `RepoStatus`, не по полям).
- **Health-checks, push-уведомления, история/тренды** — соседние пункты
  `POST_MVP_PLAN.md` (§4, §5, продолжение §3), вне рамок этой задачи.
- **TTL/возраст кэша, конфигурируемый путь кэша** — не про эту задачу вообще.

## 8. Риски / на что обратить внимание при код-ревью

- `orderBy` на `pullRequests` — валидное поле схемы GitHub GraphQL
  (`IssueOrder`/`PullRequestOrder` с `field: CREATED_AT`); если `gh api graphql`
  вернёт ошибку валидации схемы на реальном вызове (не в юнит-тестах — они
  используют захардкоженные JSON-фикстуры, не реальный `gh`), это всплывёт
  только на живой ручной проверке (`./gradlew run`), не в `./gradlew build`.
  Стоит явно прогнать `./gradlew run --args="--cli status"` с реальным `gh`
  на шаге финальной проверки, а не полагаться только на юнит-тесты.
- Порядок построения `RepoStatus` в `parseRepoNode` — в файле есть **два**
  места, где строится `RepoStatus` (happy path и "no commits" early-return);
  легко забыть прокинуть `oldestOpenPr` в оба.
- `PrInfo.createdAt` как `String` (ISO-8601) и сравнение через
  `localeCompare`/лексикографически на клиенте — работает только если формат
  дат стабилен (GitHub GraphQL всегда отдаёт `DateTime` в ISO-8601 UTC,
  `2026-07-01T10:00:00Z`) — не заводить парсинг дат в JS без необходимости.

## 9. Статус: реализовано и смержено (2026-08-22)

Реализация полностью соответствует плану (подтверждено независимым тестированием
и ревью — расхождений код/план не найдено). Смержено в `master` через
[PR #11](https://github.com/akomyagin/aiProjectDashboard/pull/11), CI зелёный.
Единственная правка сверх плана — найденная на независимом ревью низкокритичная
находка: `href` со ссылкой на самый долгий PR не блокировал `javascript:`-схему
(`esc()` экранирует только `&<>"`, не схему URL); исправлено guard'ом на
`https://`-префикс в `index.html` перед мерджем.

**Попутно найден, но НЕ исправлен в этой задаче** (вне скоупа §8, pre-existing
баг, не введён этой веткой): колонка «Open PRs» в таблице `Portfolio status`
(`index.html`, `<td>${r.openPrs}</td>` в `show()`) рендерит буквальный текст
`"undefined"` для репозиториев с 0 открытых PR — `RepoStatus.openPrs: Int = 0`
не попадает в JSON `/api/status`, т.к. `Json { prettyPrint = true }`
(`http/Server.kt`) не включает `encodeDefaults`, а kotlinx.serialization по
умолчанию не пишет поля со значением по умолчанию. Новый блок `#summary` этой
багой не задет (там уже стоит `(r.openPrs || 0)`). Зафиксировано как отдельная
задача (spawn_task `task_04b03da5`, заголовок «Исправить undefined в колонке
Open PRs при openPrs=0») — при возврате к этой теме искать через список задач
диспетчера или чинить по описанию выше напрямую.
