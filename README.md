# aiProjectDashboard

[![CI](https://github.com/akomyagin/aiProjectDashboard/actions/workflows/ci.yml/badge.svg)](https://github.com/akomyagin/aiProjectDashboard/actions/workflows/ci.yml)

Личный ops-дашборд портфеля pet-проектов на **Kotlin/Ktor**. Один экран для
состояния всех репозиториев автора: CI-статус, последний коммит, открытые PR —
плюс кросс-репо агрегатор `TODO/FIXME/HACK` с AI-приоритизацией.

Локальный on-demand инструмент: слушает только `127.0.0.1`, ничего не хранит,
не требует VPS. Токен GitHub не хранится приложением — переиспользуется
существующая сессия `gh auth`.

> Полное видение — [`docs/PLAN.md`](docs/PLAN.md); стек, архитектура и этапы —
> [`docs/TECHNICAL_PLAN.md`](docs/TECHNICAL_PLAN.md); за пределами MVP —
> [`docs/POST_MVP_PLAN.md`](docs/POST_MVP_PLAN.md).

## Возможности (MVP, две фазы)

- **Фаза 1 — статус портфеля:** CI (последний GitHub Actions run), последний
  коммит, число открытых PR по каждому репозиторию из конфига (запрашивается
  конкурентно).
- **Фаза 2 — кросс-репо TODO:** сканирует локальные `.kt/.go/.ts/.tsx/.py/.php`
  файлы всех репозиториев, собирает маркер-комментарии, ранжирует по важности.

## Требования

- JDK 17+ (в этом окружении — через SDKMAN: `source ~/.sdkman/bin/sdkman-init.sh`).
- `gh` CLI, авторизованный: `gh auth login` (для Фазы 1).

## Запуск

```bash
source ~/.sdkman/bin/sdkman-init.sh    # если JDK/Gradle стоят через SDKMAN

# Веб-дашборд (по умолчанию): http://127.0.0.1:8087
./gradlew run

# Headless CLI:
./gradlew run --args="--cli status"
./gradlew run --args="--cli todos"

# Свой конфиг:
./gradlew run --args="--config ~/.config/aiProjectDashboard/config.json"
```

Сборка и тесты:

```bash
./gradlew build      # компиляция + тесты + дистрибутив
./gradlew test
```

Тот же `./gradlew build` гоняется в CI (GitHub Actions,
[`.github/workflows/ci.yml`](.github/workflows/ci.yml)) на каждый push/PR в `master`.

## Конфигурация

Портфель, маркеры, расширения и порт настраиваются в JSON. Пример —
[`config.example.json`](config.example.json). По умолчанию (без файла)
используется встроенный default-портфель. Ожидаемый путь конфига:
`~/.config/aiProjectDashboard/config.json` (или через `--config`).

```jsonc
{
  "port": 8087,
  "markers": ["TODO", "FIXME", "HACK", "XXX"],
  "scan_extensions": [".kt", ".go", ".ts", ".tsx", ".py", ".php"],
  "repos": [
    { "name": "gitl", "owner": "akomyagin", "local_path": "~/Projects/ai-projects/gitl" }
  ]
}
```

## AI-приоритизация TODO (опционально)

По умолчанию TODO ранжируются офлайн-эвристикой (без сети и ключа). Чтобы
включить реальный LLM-ранкер (OpenAI-совместимый `/chat/completions`), задайте
переменную окружения перед запуском:

```bash
export DASHBOARD_LLM_API_KEY=sk-...
# опционально: DASHBOARD_LLM_BASE_URL (по умолчанию https://api.openai.com/v1),
#              DASHBOARD_LLM_MODEL (по умолчанию gpt-4o-mini)
```

Ключ никогда не хранится в конфиге/репозитории. Без ключа, при сетевой ошибке
или невалидном ответе LLM — тихий откат на эвристику.

## Лицензия

MIT — см. [`LICENSE`](LICENSE).
