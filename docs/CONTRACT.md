# CQA-FRDM — CONTRACT (single source of truth for people and AI agents)

> AI agents: read ONLY this file + the files of your own zone. Do not read the PDFs.
> Never change another zone's files or this contract without team agreement.

## 1. Stack (fixed)
- Java 21, Spring Boot 3.5.x (Maven wrapper), Spring Web, Spring Data JPA, Flyway, PostgreSQL 16 (docker compose).
- Frontend: plain HTML/CSS/JS (no build, no framework) in separate folder `frontend/`, served on `http://localhost:5500` (Live Server or `python -m http.server 5500`). Calls backend `http://localhost:8080/api/**` (backend has CorsConfig for :5500). Mounts into `<div id="cqa-root">` (Dynamics 365 web-resource friendly: no global CSS on `body`, all classes prefixed `cqa-`).
- LLM: OpenAI-compatible Chat Completions over HTTP (`java.net.http.HttpClient` or `RestClient`). If `LLM_API_KEY` is empty -> `MockLlmClient` (deterministic) is used.
- Secrets only in env / `.env` (gitignored). `.env.example` has empty values.

```
ORGANIZER_DATASET_URL=   # the copied DEV GET link; backend replaces the group_id query param
LLM_BASE_URL=            # e.g. https://api.openai.com/v1
LLM_API_KEY=
LLM_MODEL=
LLM_PRICE_IN_PER_1M=     # USD, for cost estimate
LLM_PRICE_OUT_PER_1M=
DB_URL=jdbc:postgresql://localhost:5432/cqa
DB_USER=cqa
DB_PASSWORD=cqa
```

## 2. Zones (who edits what)
| # | Zone | Files |
|---|------|-------|
| 1 Backend/DB/integration | `pom.xml`, `docker-compose.yml`, `db/migration/*`, packages `dataset`, `conversation`, `checklist`, `appeal`, `common` (incl. CorsConfig), `EvaluationController/Service`, `README.md` |
| 2 AI/Scoring | package `evaluation.ai` (LlmClient, OpenAiLlmClient, MockLlmClient, PromptBuilder, LlmResponseValidator), `evaluation.scoring.ScoringEngine`, `db/migration/V2__seed_checklist.sql`, tests in `src/test/.../scoring` |
| 3 Frontend conversations | `frontend/**` (whole frontend, 3-person team) |

Base package: `com.cqafrdm`. Branches: `feature/backend-data`, `feature/ai-scoring`, `feature/frontend-conversations`, `feature/frontend-checklists-appeals`. PR into `main`.

## 3. DB (Flyway `V1__init.sql`) — JSONB instead of 10 tables
```sql
create table conversation (
  id text primary key,                 -- conversation_id from organizer
  group_id text not null,
  channel text not null,               -- 'agent chat' | 'voice call'
  language text not null,              -- RU | KZ | EN
  audio_url text,
  transcript jsonb not null,           -- raw array, stored EXACTLY as received
  imported_at timestamptz not null default now()
);
create table checklist_version (
  id bigserial primary key,
  name text not null,
  version int not null,
  criteria jsonb not null,             -- Criterion[] (immutable once saved)
  created_at timestamptz not null default now(),
  unique (name, version)
);
create table evaluation (
  id bigserial primary key,
  conversation_id text not null references conversation(id),
  checklist_version_id bigint not null references checklist_version(id),
  status text not null,                -- OK | FAILED
  result jsonb,                        -- EvaluationResult (original, never overwritten)
  total_score numeric(5,2),            -- null if no applicable weighted criteria
  gross_error boolean,
  model text, input_tokens int, output_tokens int, cost_usd numeric(10,6),
  error text,
  created_at timestamptz not null default now()
);
create table appeal (
  id bigserial primary key,
  evaluation_id bigint not null references evaluation(id),
  criterion_id text not null,
  comment text not null,
  evidence_ids jsonb,                  -- int[]
  status text not null default 'OPEN', -- OPEN | DECIDED
  decision text,                       -- CONFIRMED | CHANGED
  new_answer text, new_is_na boolean,
  reviewer_reason text,
  final_result jsonb,                  -- EvaluationResult after decision
  final_total numeric(5,2),
  created_at timestamptz not null default now(),
  decided_at timestamptz
);
```
JPA: map jsonb with `@JdbcTypeCode(SqlTypes.JSON)`. Import is upsert by `id` (no duplicates).

## 4. JSON types
**Message id = 0-based index in `transcript` array.** Never re-number, never edit timestamps.

```jsonc
// Conversation
{ "id":"p1-ru-001", "groupId":"DEV", "channel":"agent chat", "language":"RU",
  "audioUrl":null, "transcript":[{"timestamp":"00:02","speaker":"operator","text":"..."}] }

// Criterion  (type: BINARY | SCALE | FLAG)
{ "id":"c4", "title":"Корректная и полная консультация", "section":"Безопасность и решение",
  "rule":"...", "type":"SCALE", "weight":20, "naAllowed":true, "naCondition":"...",
  "scale":[{"value":20,"label":"..."},{"value":15,"label":"..."},{"value":10,"label":"..."},{"value":5,"label":"..."},{"value":0,"label":"..."}] }

// ChecklistVersion
{ "id":1, "name":"default", "version":1, "criteria":[Criterion], "createdAt":"..." }

// CriterionResult
{ "criterionId":"c4", "title":"...", "type":"SCALE", "weight":20,
  "answer":"15",            // BINARY/FLAG: "YES"|"NO"; SCALE: scale value as string; null when isNa
  "isNa":false, "points":15.0,  // null when isNa or FLAG
  "reason":"short justification (in conversation language or RU)",
  "evidenceIds":[4,6],      // must exist in transcript
  "checkedRange":{"from":3,"to":8},   // required when action missing (answer NO / low score)
  "needsReview":false,                // true = 2nd (verifier) model disagrees -> show ⚠, human should look; null = no check
  "verification":"✓ Подтверждено второй моделью: ..." }

// EvaluationResult
{ "criteria":[CriterionResult], "totalScore":87.5, "grossError":false }

// EvaluationDto (GET)
{ "id":12, "conversationId":"...", "checklistVersionId":1, "status":"OK", "error":null,
  "original":EvaluationResult, "appeals":[AppealDto], "final":EvaluationResult,
  "model":"...", "costUsd":0.0021, "createdAt":"..." }
// final = result of latest DECIDED appeal applied on top of original (or original)

// AppealDto
{ "id":3, "evaluationId":12, "criterionId":"c4", "comment":"...", "evidenceIds":[5],
  "status":"DECIDED", "decision":"CHANGED", "newAnswer":"20", "newIsNa":false,
  "reviewerReason":"...", "finalTotal":92.31, "createdAt":"...", "decidedAt":"..." }

// Error (all 4xx/5xx)
{ "error":"CODE", "message":"human readable" }
```

## 5. REST API (backend :8080)
| Method | Path | Body / query | Returns |
|---|---|---|---|
| POST | `/api/datasets/import` | `{"groupId":"DEV"}` | `{"groupId","imported","updated","total"}` |
| GET | `/api/conversations` | `?groupId=&language=&channel=` | `[{id,groupId,channel,language,preview,messageCount,lastEvaluation:{id,totalScore,grossError,status}|null}]` |
| GET | `/api/conversations/{id}` | | `Conversation` |
| GET | `/api/checklists` | | `ChecklistVersion[]` (newest first) |
| GET | `/api/checklists/{id}` | | `ChecklistVersion` |
| POST | `/api/checklists` | `{"name","criteria":[...]}` | new `ChecklistVersion` (version = max+1; old ones untouched) |
| POST | `/api/evaluations` | `{"conversationId","checklistVersionId"}` | `EvaluationDto` |
| POST | `/api/evaluations/batch` | `{"groupId","checklistVersionId"}` | `{"started":N}` (async, max 3 parallel) |
| GET | `/api/evaluations/{id}` | | `EvaluationDto` |
| GET | `/api/evaluations` | `?conversationId=` | `EvaluationDto[]` |
| POST | `/api/appeals` | `{"evaluationId","criterionId","comment","evidenceIds":[]}` | `AppealDto` |
| GET | `/api/appeals` | `?status=OPEN` | `AppealDto[]` (+conversationId) |
| PATCH | `/api/appeals/{id}` | `{"decision":"CONFIRMED"|"CHANGED","newAnswer","newIsNa","reason"}` | `AppealDto` |

Validation on PATCH/POST checklist: answer must be valid for criterion type; NA only if `naAllowed`; weights > 0 for BINARY/SCALE; FLAG weight 0.

## 6. AI evaluation (zone 2)
- ONE LLM call per conversation, all criteria at once, `temperature=0`, `response_format={"type":"json_object"}`.
- Prompt input: criteria of the selected version (id, title, rule, type, scale, naAllowed, naCondition) + transcript as lines `[i] (timestamp) speaker: text`.
- Prompt rules: evaluate only operator; bot/system = context; NO vs NA difference; evidence = existing indices; missing action -> checkedRange; do not invent.
- LLM returns `{"criteria":[{"criterion_id","answer","is_na","reason","evidence_ids","checked_range":{"from","to"}}]}`.
- Validator: every criterion present once; answer allowed; NA allowed; indices in range. On failure -> 1 retry with error text; still invalid -> evaluation `status=FAILED` (no fake score).
- Points are computed in Java by `ScoringEngine`, never by the LLM.
- Cost: `in_tokens*PRICE_IN/1e6 + out_tokens*PRICE_OUT/1e6`, summed over all calls (incl. retry).

## 7. ScoringEngine (pure, JUnit-tested)
```
BINARY: YES -> weight, NO -> 0
SCALE : points = weight * value / max(scale.values)
NA    : excluded from both sums
FLAG  : no points; grossError = (answer == YES)
total = sumApplicable==0 ? null : round_half_up(100 * sum(points) / sum(applicable weights), 2)
```
Tests: all YES -> 100; c6=7 -> 10.5 pts; one NA redistribution; all NA -> null; appeal override recompute; rounding.

## 8. Default checklist (V2 seed, name "default", version 1)
| id | title | type | weight | NA | scale |
|---|---|---|---|---|---|
| c1 | Установление контакта | BINARY | 10 | no | |
| c2 | Выявление потребности | BINARY | 15 | yes | |
| c3 | Идентификация | BINARY | 20 | yes | |
| c4 | Корректная и полная консультация | SCALE | 20 | yes | 0,5,10,15,20 |
| c5 | Эмпатия и клиентоориентированность | BINARY | 15 | no | |
| c6 | Управление диалогом, скорость и грамотность | SCALE | 15 | no | 0,4,7,10 |
| c7 | Завершение | BINARY | 5 | yes | |
| c8 | Грубые ошибки | FLAG | 0 | no | |
Rule texts and scale labels: copy from CQA_Chat_Quality_Checklist.pdf (zone 2 owns). Note: 3-minute first-response threshold, tags and attachments are NOT evaluated.

## 9. Frontend screens (hash routing, one `index.html`)
- `#/` list: filters language/channel/groupId, score badge, gross-error badge, "Import group_id" input+button, "Evaluate all" button (zone 3).
- `#/c/{id}` card: transcript (role colors, timestamps), `<audio>` for voice call; evidence click -> scroll + highlight `[data-msg="i"]`, checkedRange shaded; for calls seek `audio.currentTime = ts(i) - ts(0)` (ISO). Right panel: checklist version select, "Evaluate", per-criterion result, total, gross flag, model+cost (zone 3). "Appeal" button per criterion -> calls `CQA.appeals.openForm(evaluation, criterionId)` (zone 3).
- `#/checklists` editor: list versions, edit criteria (title, rule, type, weight, scale, naAllowed, naCondition), add/remove, "Save as new version" (zone 3).
- `#/appeals` reviewer: open appeals, shows disputed criterion, comment, evidence snippet, CONFIRM / CHANGE + reason; card shows original vs final (zone 3).
- Shared: `window.CQA = { api, ui, appeals, checklist }`; `api.js` exports `CQA.api.get/post/patch` returning JSON or throwing `{error,message}`.

## 10. Additions (results submission, list summary)
| Method | Path | Query | Returns |
|---|---|---|---|
| GET | `/api/results/export` | `groupId` (req), `checklistVersionId`, `source=final|original` | results file, schema 1.0 (see Student Guide appendix) |

- Codes: c1..c8 → contact, discovery, identification, consultation, empathy, dialogue, closure, critical.
- `value`: true/false/null(N/A)/scale number; `evidence_turns` = evidence index + 1 (1-based, original transcript order);
  `final_score` recomputed over the 8 codes with N/A weight redistribution; `critical_flag` = critical value.
- Not evaluated / FAILED → `status:"error"`, `criteria:[]`, `score:null`, `error`. One record per conversation of the group.
- `GET /api/conversations` → `lastEvaluation` now also has `needsReviewCount` and `grossReason`; scores are from `final` (after appeals).
- `preview` = first client message.
