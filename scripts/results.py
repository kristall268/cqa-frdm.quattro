#!/usr/bin/env python3
"""
CQA — выгрузка и проверка results.json (приложение к гайду, формат JSON 1.0).

Только стандартная библиотека Python 3.8+. Backend должен быть запущен (по умолчанию http://localhost:8080).

  # DEV: выгрузить уже оценённые диалоги
  python scripts/results.py export --group DEV --out results/dev/results.json

  # PROD (1 час до стоп-кода): импорт -> оценка всех -> повтор упавших -> выгрузка -> проверка
  python scripts/results.py prod --group <ID_КОНТРОЛЬНОЙ_ГРУППЫ> --expect 10

  # Проверить файл перед commit/push
  python scripts/results.py validate results/prod/results.json --expect 10
"""
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

CODES = ["contact", "discovery", "identification", "consultation", "empathy", "dialogue", "closure", "critical"]
ALLOWED = {
    "contact": (True, False),
    "discovery": (True, False, None),
    "identification": (True, False, None),
    "consultation": (0, 5, 10, 15, 20, None),
    "empathy": (True, False),
    "dialogue": (0, 4, 7, 10),
    "closure": (True, False, None),
    "critical": (True, False),
}
MODES = ("local", "external_api", "hybrid")


# ------------------------------------------------------------------ HTTP
def call(api, method, path, body=None, timeout=180):
    data = None if body is None else json.dumps(body).encode("utf-8")
    req = urllib.request.Request(api.rstrip("/") + path, data=data, method=method,
                                 headers={"Content-Type": "application/json", "Accept": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read().decode("utf-8")
            return json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        try:
            err = json.loads(e.read().decode("utf-8"))
            msg = err.get("message") or err.get("error")
        except Exception:
            msg = str(e)
        raise SystemExit(f"[API] {method} {path}: HTTP {e.code}: {msg}")
    except urllib.error.URLError as e:
        raise SystemExit(f"[API] backend недоступен ({api}): {e.reason}. Запустите backend.")


def q(**params):
    p = {k: v for k, v in params.items() if v not in (None, "")}
    return ("?" + urllib.parse.urlencode(p)) if p else ""


def latest_checklist(api):
    versions = call(api, "GET", "/api/checklists")
    if not versions:
        raise SystemExit("Нет ни одной версии чек-листа")
    return versions[0]["id"]  # newest first


# ------------------------------------------------------------------ validate
def validate(doc, expect=None):
    """Возвращает (ошибки, предупреждения). Правила — из приложения к гайду."""
    errors, warns = [], []
    if not isinstance(doc, dict):
        return ["Корень должен быть JSON-объектом"], warns
    if doc.get("schema_version") != "1.0":
        errors.append('schema_version должен быть "1.0"')
    if not isinstance(doc.get("team_name"), str) or not doc.get("team_name").strip():
        errors.append("team_name пуст")
    m = doc.get("model")
    if not isinstance(m, dict) or m.get("mode") not in MODES or not str(m.get("name") or "").strip():
        errors.append("model: нужен {mode: local|external_api|hybrid, name: ...}")
    elif "mock" in str(m.get("name")).lower():
        warns.append("model.name содержит mock — оценки сделаны без настоящей модели (задайте LLM_API_KEY)")
    res = doc.get("results")
    if not isinstance(res, list):
        return errors + ["results должен быть массивом"], warns
    if expect is not None and len(res) != expect:
        errors.append(f"ожидается {expect} записей, в файле {len(res)}")
    seen = set()
    for i, r in enumerate(res):
        cid = r.get("conversation_id") if isinstance(r, dict) else None
        where = f"results[{i}] ({cid})"
        if not cid:
            errors.append(f"{where}: нет conversation_id")
            continue
        if cid in seen:
            errors.append(f"{where}: повторяющийся conversation_id")
        seen.add(cid)
        st = r.get("status")
        if st == "error":
            if r.get("criteria") != [] or r.get("score") is not None or not r.get("error"):
                errors.append(f"{where}: для status=error нужны criteria=[], score=null и текст error")
            else:
                warns.append(f"{where}: status=error — {r.get('error')}")
            continue
        if st != "ok":
            errors.append(f"{where}: status должен быть ok или error")
            continue
        crit = r.get("criteria")
        if not isinstance(crit, list):
            errors.append(f"{where}: criteria не массив")
            continue
        ids = [c.get("criterion_id") for c in crit]
        if sorted(ids) != sorted(CODES) or len(ids) != 8:
            errors.append(f"{where}: нужны все 8 критериев ровно по разу, есть {ids}")
        values = {}
        for c in crit:
            code = c.get("criterion_id")
            if code not in ALLOWED:
                continue
            v = c.get("value")
            ok = any(v is a for a in ALLOWED[code] if isinstance(a, bool) or a is None) or \
                (type(v) is int and v in [a for a in ALLOWED[code] if type(a) is int])
            if not ok:
                errors.append(f"{where}: {code}.value={v!r} недопустимо, ожидается {ALLOWED[code]}")
            values[code] = v
            if not isinstance(c.get("rationale"), str) or not c.get("rationale").strip():
                warns.append(f"{where}: {code} без rationale")
            t = c.get("evidence_turns")
            if not isinstance(t, list) or any(type(x) is not int or x < 1 for x in t):
                errors.append(f"{where}: {code}.evidence_turns — список номеров реплик с 1")
        s = r.get("score")
        if not isinstance(s, dict):
            errors.append(f"{where}: нет score")
            continue
        fs = s.get("final_score")
        if fs is not None and (not isinstance(fs, (int, float)) or isinstance(fs, bool) or fs < 0 or fs > 100
                               or round(fs, 2) != fs):
            errors.append(f"{where}: final_score должен быть числом 0..100 с 2 знаками")
        if s.get("critical_flag") is not values.get("critical"):
            errors.append(f"{where}: critical_flag должен совпадать с value критерия critical")
    return errors, warns


def write(doc, out):
    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        json.dump(doc, f, ensure_ascii=False, indent=2)
        f.write("\n")


def report(doc, errors, warns, out=None):
    res = doc.get("results") or []
    ok = sum(1 for r in res if r.get("status") == "ok")
    crit = sum(1 for r in res if r.get("status") == "ok" and (r.get("score") or {}).get("critical_flag"))
    print(f"Записей: {len(res)} · ok: {ok} · error: {len(res) - ok} · грубые ошибки: {crit} · модель: "
          f"{(doc.get('model') or {}).get('mode')} / {(doc.get('model') or {}).get('name')}")
    for w in warns:
        print("  ! " + w)
    for e in errors:
        print("  ✗ " + e)
    if out:
        print(("✓ Файл записан: " if not errors else "✗ Файл записан С ОШИБКАМИ: ") + out)
    print("Далее: git add results && git commit -m 'results' && git push" if not errors else "")


# ------------------------------------------------------------------ commands
def cmd_export(a):
    doc = call(a.api, "GET", "/api/results/export" + q(groupId=a.group, checklistVersionId=a.checklist, source=a.source))
    errors, warns = validate(doc, a.expect)
    write(doc, a.out)
    report(doc, errors, warns, a.out)
    return 1 if errors else 0


def cmd_validate(a):
    with open(a.file, encoding="utf-8") as f:
        doc = json.load(f)
    errors, warns = validate(doc, a.expect)
    report(doc, errors, warns)
    return 1 if errors else 0


def snapshot(api, group):
    rows = call(api, "GET", "/api/conversations" + q(groupId=group))
    return {r["id"]: (r.get("lastEvaluation") or {}) for r in rows}


def cmd_prod(a):
    t0 = time.time()
    print(f"1/5 Импорт группы {a.group}…")
    r = call(a.api, "POST", "/api/datasets/import", {"groupId": a.group})
    print(f"    получено {r.get('total')} диалогов (новых {r.get('imported')}, обновлено {r.get('updated')})")
    checklist = a.checklist or latest_checklist(a.api)
    before = {k: v.get("id") for k, v in snapshot(a.api, a.group).items()}
    print(f"2/5 Запуск оценки (чек-лист #{checklist})…")
    started = call(a.api, "POST", "/api/evaluations/batch", {"groupId": a.group, "checklistVersionId": checklist})
    total = started.get("started", len(before))
    deadline = time.time() + a.wait * 60
    while True:
        snap = snapshot(a.api, a.group)
        done = {k: v for k, v in snap.items() if v.get("id") and v.get("id") != before.get(k)}
        failed = [k for k, v in done.items() if v.get("status") == "FAILED"]
        print(f"    {len(done)}/{total} готово, FAILED: {len(failed)} ({int(time.time() - t0)} с)", flush=True)
        if len(done) >= total or time.time() > deadline:
            break
        time.sleep(10)
    print("3/5 Повтор для упавших и неоценённых…")
    snap = snapshot(a.api, a.group)
    for cid, v in sorted(snap.items()):
        fresh = v.get("id") and v.get("id") != before.get(cid)
        if not fresh or v.get("status") == "FAILED":
            ev = call(a.api, "POST", "/api/evaluations", {"conversationId": cid, "checklistVersionId": checklist}, timeout=300)
            print(f"    {cid}: {ev.get('status')}" + (f" — {ev.get('error')}" if ev.get("error") else ""))
    print("4/5 Выгрузка…")
    doc = call(a.api, "GET", "/api/results/export" + q(groupId=a.group, checklistVersionId=checklist, source=a.source))
    write(doc, a.out)
    print("5/5 Проверка формата…")
    errors, warns = validate(doc, a.expect)
    report(doc, errors, warns, a.out)
    print(f"Всего: {int(time.time() - t0)} с")
    return 1 if errors else 0


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--api", default=os.environ.get("CQA_API", "http://localhost:8080"))
    sub = p.add_subparsers(dest="cmd", required=True)

    e = sub.add_parser("export", help="выгрузить results.json из backend")
    e.add_argument("--group", required=True)
    e.add_argument("--out", default="results/dev/results.json")
    e.add_argument("--checklist", type=int, help="ID версии чек-листа (по умолчанию — последняя оценка)")
    e.add_argument("--source", choices=["final", "original"], default="final",
                   help="final — с учётом апелляций, original — исходная оценка AI")
    e.add_argument("--expect", type=int)
    e.set_defaults(fn=cmd_export)

    pr = sub.add_parser("prod", help="импорт -> оценка -> повтор -> выгрузка -> проверка")
    pr.add_argument("--group", required=True)
    pr.add_argument("--out", default="results/prod/results.json")
    pr.add_argument("--checklist", type=int, help="ID версии (по умолчанию — новейшая)")
    pr.add_argument("--source", choices=["final", "original"], default="original")
    pr.add_argument("--expect", type=int, default=10)
    pr.add_argument("--wait", type=int, default=30, help="максимум минут ожидания пакетной оценки")
    pr.set_defaults(fn=cmd_prod)

    v = sub.add_parser("validate", help="проверить готовый файл")
    v.add_argument("file")
    v.add_argument("--expect", type=int)
    v.set_defaults(fn=cmd_validate)

    a = p.parse_args()
    sys.exit(a.fn(a))


if __name__ == "__main__":
    main()
