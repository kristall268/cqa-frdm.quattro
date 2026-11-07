# CQA · Freedom Quality Analytics

Система оценки качества работы операторов: импортирует чаты и звонки из API организаторов, оценивает каждый диалог по
версионируемому чек-листу с помощью LLM, объясняет каждую оценку ссылками на реплики, отдельно помечает грубые ошибки,
перепроверяет спорные оценки второй моделью и даёт человеку изменить решение через апелляцию.

**Принципы:** модель не считает баллы (это детерминированный `ScoringEngine`); каждый вывод ссылается на реплики;
ответ модели проходит валидацию, иначе 1 повтор, затем `FAILED` — **без выдуманной оценки**; исходная оценка AI никогда
не перезаписывается — решения по апелляциям хранятся отдельно.

## Возможности

| Сценарий | Где в интерфейсе |
|---|---|
| Импорт группы (DEV / контрольный набор) и массовая оценка | **Диалоги** → «Импорт группы», «Оценить группу» |
| Все диалоги с грубыми ошибками и объяснением | **Диалоги** → «Грубые ошибки» (фильтр + причина под каждым диалогом) |
| Результат конкретного диалога с обоснованием | Карточка диалога: итог, баллы по критериям, обоснование, переход к репликам, проверенный участок, N/A |
| Спорные оценки (вторая модель не согласна) | Метка **⚠ Проверить** в списке и в карточке, фильтр «Требуют проверки» |
| Апелляция и решение проверяющего | Кнопка «Апелляция» у критерия → **Апелляции**: подтвердить / изменить + причина; исходная и финальная оценки видны рядом |
| Изменить чек-лист: добавить критерий, правило, вес, шкалу, N/A | **Чек-листы** → «Сохранить как новую версию» (старые версии не меняются), затем оценка по новой версии |
| Выгрузка `results.json` (формат JSON 1.0) | **Результаты** → «Сформировать» → проверка формата → «Скачать» |
| Звонки | Плеер записи, клик по доказательству перематывает аудио; промпт учитывает ошибки распознавания речи |

## Архитектура

```
frontend/  plain HTML/CSS/JS (без сборки), :5500 ──REST──▶ backend/  Spring Boot 3.5, Java 21, :8080 ──▶ PostgreSQL 16
                                                              │
                                                              ├─ PromptBuilder ─▶ LLM (OpenAI-совместимый: Gemini / Groq / OpenAI / Ollama)
                                                              ├─ LlmResponseValidator ─(ошибка)─▶ 1 повтор ─▶ FAILED
                                                              ├─ ScoringEngine (баллы, N/A, итог) ─▶ AnswerVerifier (2-я модель, needsReview)
                                                              └─ ResultsExportService ─▶ results/{dev,prod}/results.json
```

Формула: Да = вес, Нет = 0, шкала = вес × ступень / максимум; N/A исключается, его вес перераспределяется;
итог = 100 × Σбаллов / Σприменимых весов, 2 знака HALF_UP. Грубая ошибка — отдельный флаг, итог не обнуляет.
Подробно: [docs/AI.md](docs/AI.md), API: [docs/CONTRACT.md](docs/CONTRACT.md).

## Запуск

Нужны: Java 21, Python 3 (для статического сервера и скрипта результатов), PostgreSQL 16 (или Docker).

```bash
cp .env.example .env              # заполнить ORGANIZER_DATASET_URL и модель (см. ниже)
docker compose up -d db           # PostgreSQL (без Docker: создать БД cqa, пользователь cqa/cqa)
cd backend && ./mvnw spring-boot:run          # Windows: mvnw.cmd spring-boot:run  → http://localhost:8080
cd frontend && python -m http.server 5500     # → http://localhost:5500
```

Всё сразу в Docker: `docker compose --profile app up --build` (backend + БД), frontend — как выше.
Демо интерфейса без backend: `http://localhost:5500/?mock=1` (синтетические данные, помечено DEMO · MOCK).

### Модель: облако, локально или гибрид

| Режим | `.env` |
|---|---|
| Облако (Gemini) | `LLM_BASE_URL=https://generativelanguage.googleapis.com/v1beta/openai`, `LLM_API_KEY=…`, `LLM_MODEL=gemini-…` |
| Локально (Ollama) | `LLM_BASE_URL=http://localhost:11434/v1`, `LLM_MODEL=qwen2.5:14b` (ключ не нужен) |
| Гибрид | основная модель локально + `LLM_VERIFIER_*` или `LLM_FALLBACK_*` в облаке; `RESULTS_MODEL_MODE=hybrid` |

`temperature=0`, ответ — строгий JSON. Без ключа и адреса работает `MockLlmClient` (только для разработки, в причинах пометка MOCK).

## Сдача результатов

```bash
# DEV (60 чатов + 5 звонков): оценить группу в интерфейсе или скриптом, затем выгрузить
python scripts/results.py export --group DEV --out results/dev/results.json --expect 65

# Контрольный набор, 1 час до стоп-кода: импорт → оценка → повтор упавших → выгрузка → проверка
python scripts/results.py prod --group <ID> --expect 10

python scripts/results.py validate results/prod/results.json --expect 10
git add results && git commit -m "results: prod" && git push
```

Скрипт использует только стандартную библиотеку Python. Он проверяет схему, коды и типы значений, уникальность ID,
нумерацию реплик с 1, `critical_flag = critical`, число записей. Неоценённые диалоги попадают в файл как
`status: "error"` с причиной — не скрываются и не заменяются нулём.

## Тесты

```bash
cd backend && ./mvnw test -Dtest="ScoringEngineTest,EvaluationRunnerTest,ResultsMapperTest"
```

Покрыто: шкала и веса, N/A и перераспределение, итог `null`, округление, грубая ошибка без обнуления, апелляция
с сохранением исходной оценки, повтор и FAILED, запрет бота как доказательства, проверенный участок для «Нет»,
новый критерий без изменения кода, вторая модель, формат results.json (коды, значения, нумерация с 1, final_score).

## Структура

```
backend/            Spring Boot: dataset, conversation, checklist, evaluation(ai, scoring), appeal, results, common
frontend/           index.html, css/, js/ (api, router, ui, conversations, card, checklist, appeals, results)
scripts/results.py  выгрузка / пакетный прогон / проверка results.json
results/            dev/results.json, prod/results.json
docs/               CONTRACT.md, AI.md, FRONTEND.md
```

Секреты только в `.env` (в `.gitignore`). URL организаторов и ключи не попадают во frontend и логи.
