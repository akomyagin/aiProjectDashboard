# CLAUDE.md

Инструкции для AI-сессий (Claude Code) в репозитории `aiProjectDashboard`. Эти
инструкции имеют приоритет над поведением по умолчанию — следуй им точно.

## Что это за репозиторий

`aiProjectDashboard` — личный ops-дашборд портфеля pet-проектов на **Kotlin/Ktor**:
агрегирует CI-статус, последние коммиты и открытые PR всех репозиториев автора, плюс
кросс-репо агрегатор `TODO/FIXME/HACK` с AI-приоритизацией. Соло pet-проект,
учебная цель — **чистый бэкенд на Ktor** (сознательно другая «мышца» Kotlin, чем у
IDE-плагина `orm-nplus1-radar` из того же портфеля). Локальный on-demand инструмент,
слушает только `127.0.0.1`, без VPS, расходы $0/мес.

Полное видение — [`docs/PLAN.md`](docs/PLAN.md); технический план и разбивка по
этапам — [`docs/TECHNICAL_PLAN.md`](docs/TECHNICAL_PLAN.md); за пределами MVP —
[`docs/POST_MVP_PLAN.md`](docs/POST_MVP_PLAN.md).

## Структура репозитория

| Путь | Содержимое |
|---|---|
| `docs/` | `PLAN.md`, `TECHNICAL_PLAN.md`, `POST_MVP_PLAN.md` |
| `.claude/skills/ktor-ops-dashboard-dev/SKILL.md` | Конвенции именно этого проекта для написания кода |
| `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties` | Gradle (Kotlin DSL) + wrapper 8.10.2 |
| `src/main/kotlin/dev/akomyagin/dashboard/` | Ядро: `Main.kt`, `DashboardService.kt`, пакеты `config/`, `github/`, `scan/`, `rank/`, `http/`, `cli/` |
| `src/main/resources/static/index.html` | Статический веб-UI (vanilla JS, две вкладки) |
| `src/test/kotlin/…` | Тесты (scanner, ranker, config, CI-mapping; далее — маршруты Ktor) |
| `config.example.json` | Пример конфига портфеля |

Docker Compose **намеренно отсутствует** — оркестровать нечего (нет БД/брокера/кэша),
единственная внешняя зависимость `gh` живёт на хосте. Обоснование — `TECHNICAL_PLAN.md §2`.

## Модель-политика (какую модель на что вызывать)

**Fable 5 не используется.**

## Git / dev-workflow (обязательный процесс на каждый этап/задачу)

Главная ветка — **`master`** (не `main`).

1. Opus 4.8 — если требуется детальное планирование этапа — планирование, затем написание кода.
2. Sonnet — проверка качества покрытия тестами, тестирование, проверка работоспособности, проверка покрытия новых функций.
3. Opus — независимое ревью: skill /code-review на diff ветки, фиксируем замечания.
4. Цикл исправлений — до 3 итераций: Sonnet правит замечания → тесты снова.
5. Commit + push + PR — conventional-commit с русским subject, PR в master-ветку.

## Конвенции проекта

- **Язык:** документация и subject коммитов — по-русски; код, идентификаторы и
  комментарии в коде — по-английски.
- **Коммиты:** conventional-commit с русским subject, напр.
  `feat(phase1): агрегация CI-статуса портфеля через gh CLI`. Завершать трейлером
  `Co-Authored-By: Claude`.
- **Ветки:** feature-ветки, PR в `master`.
- **Секреты никогда не в git/дистрибутив.** Токен GitHub не хранится приложением —
  переиспользуется `gh auth`. LLM-ключ (post-MVP) — только из переменной окружения.

## Команды

```bash
source ~/.sdkman/bin/sdkman-init.sh   # JDK 17/21 и Gradle стоят через SDKMAN

./gradlew build                       # компиляция + тесты + дистрибутив
./gradlew test                        # только тесты
./gradlew run                         # веб-дашборд на http://127.0.0.1:8087
./gradlew run --args="--cli status"   # headless: статус портфеля в терминал
./gradlew run --args="--cli todos"    # headless: ранжированные TODO
```

Перед коммитом обязателен зелёный `./gradlew build`. При проверке результата
сборки не использовать `| tail` перед `$?` — проверять `${PIPESTATUS[0]}`.

## Грабли Gradle (из `orm-nplus1-radar`)

- `org.gradle.configuration-cache=true` в `gradle.properties` оставлен **выключенным**
  (закомментирован с причиной) — на некоторых связках давал ложные ошибки сериализации.
  Не включать без проверки `./gradlew build --configuration-cache`.
- IntelliJ Platform Gradle plugin здесь не используется — это обычный Ktor-бэкенд.
