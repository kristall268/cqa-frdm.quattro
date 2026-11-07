# Результаты для сдачи (формат JSON 1.0)

| Файл | Набор | Как получить |
|---|---|---|
| `dev/results.json` | DEV: 60 чатов + 5 звонков | `python scripts/results.py export --group DEV --out results/dev/results.json --expect 65` |
| `prod/results.json` | 10 контрольных чатов | `python scripts/results.py prod --group <ID> --expect 10` |

Файлы создаются только из реальных оценок системы (backend + модель). Перед push:

```bash
python scripts/results.py validate results/prod/results.json --expect 10
git add results && git commit -m "results: prod" && git push
```

То же можно сделать в интерфейсе: раздел **Результаты** → «Сформировать» → «Скачать results.json».
