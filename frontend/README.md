# cqa-frdm — Freedom-inspired Frontend

Красивый корпоративный UI для Conversation Quality Analytics на **чистом HTML, CSS и JavaScript**. Без React, npm, сборщиков, CDN и frontend-фреймворков.

## Структура

```text
cqa-frdm/
├── frontend/           ← этот каталог (не backend/src/main/resources/static)
│   ├── index.html
│   ├── css/cqa.css
│   ├── js/
│   │   ├── config.js
│   │   ├── ui.js
│   │   ├── api.js
│   │   ├── router.js
│   │   ├── conversations.js
│   │   ├── card.js
│   │   ├── checklist.js
│   │   ├── appeals.js
│   │   └── app.js
│   └── mock/default-checklist.json
└── backend/            ← отдельный Spring Boot проект команды
```

## Запуск

```bash
cd cqa-frdm/frontend
python -m http.server 5500
```

Открыть `http://localhost:5500` (или использовать VS Code Live Server).

`js/config.js`:

```js
CQA.config = { API_BASE: 'http://localhost:8080', USE_MOCK: /* true только при ?mock=1 */ false, BRAND: 'Freedom' };
```

- `http://localhost:5500/?mock=1` — готовая интерактивная демоверсия с **12 синтетическими примерами**, не реальные данные Freedom. Для демо PostgreSQL и backend не нужны. Mock-изменения сохраняются в localStorage при доступности.
- по умолчанию — реальные REST-запросы к Spring Boot на порт 8080. API совпадает с `docs/CONTRACT.md` присланного репозитория. Backend должен разрешать origin `http://localhost:5500` в CORS.
- Не открывайте HTML через `file://`: локальный HTTP-сервер нужен для загрузки JSON.

## Рабочие экраны

- `#/` — обзор, метрики, быстрые действия и последние диалоги.
- `#/conversations` — поиск, фильтры RU/KZ/EN и чат/звонок, импорт DEV, массовая оценка.
- `#/c/{id}` — транскрипт, отдельное аудио для звонков, AI-оценка, детали всех критериев и переход к репликам, N/A, `needsReview`, грубые ошибки.
- `#/checklists` — редактор веса, типа, правила, шкалы и N/A; версионирование и новая конфигурация без изменения кода.
- `#/appeals` — очередь апелляций: подтвердить или изменить, обоснование, финальный результат.
- `#/conversations/gross` — все диалоги с грубыми ошибками и причиной; фильтр «Результат оценки» (⚠, ошибки AI, не оценены).
- `#/results` — выгрузка results.json (формат JSON 1.0) с проверкой формата и скачиванием.

## Дизайн и Dynamics

- Все стили ограничены селектором `#cqa-root`, классы имеют префикс `cqa-`.
- Используется одна глобальная переменная `window.CQA`, скрипты без ES-модулей, относительные пути.
- Нет глобального CSS для `body` / `html` (удобно при адаптации в Dynamics 365 Web Resource).
- Нативные SVG-иконки. Анимации учитывают `prefers-reduced-motion`.
- Условные тексты по умолчанию на русском; RU/KZ/EN поддерживаются **как языки диалогов**, не как полная локализация UI.
- Цветовая стилистика вдохновлена Freedom, это не официальная копия продукта или логотипа.

## Важно

Базовый mock оценивает разговоры по тестовым правилам **только для демонстрации UI**, это не реальное AI-решение. В реальном режиме итог, доказательства и критерии получает только сервер. Значения `audioUrl` у mock звонков `null`, поэтому плеер не появляется до предоставления реальной записи. Не публикуйте секретный `ORGANIZER_DATASET_URL` и API-ключи в frontend.
