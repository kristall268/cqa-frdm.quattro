# AI бағалау модулі (№2) — қалай жұмыс істейді

## 1. Қарапайым тілмен (қорғауда осыны айтасың)

1. Диалогтың әр репликасына нөмір қойылады: `[0]`, `[1]`, `[2]`…
2. Модельге **таңдалған чек-лист нұсқасы** мен нөмірленген транскрипт жіберіледі. Модель әр критерий бойынша жауап береді (Иә/Жоқ/N/A/шкала), қысқа себеп жазады және **дәлел реплика нөмірлерін** көрсетеді.
3. **Модель балл есептемейді.** Балды Java-дағы `ScoringEngine` чек-лист формуласымен есептейді. Сондықтан бірдей жауаптардан әрқашан бірдей балл шығады.
4. Модель жауабын **валидатор тексереді**:
   - барлық критерий бар ма;
   - жауап шкалаға сай ма;
   - N/A рұқсат етілген бе;
   - көрсетілген реплика шынымен бар ма;
   - «Иә» деген бағаның дәлелі бот емес, **оператордың** репликасы ма;
   - «Жоқ» деген бағада әрекет күтілген орын (`checked_range`) көрсетілген бе.
5. Жауап тексеруден өтпесе, модельге қателер тізімімен **1 рет қайта** сұрау жіберіледі. Тағы өтпесе, баға `FAILED` деп белгіленеді. **Ойдан шығарылған балл ешқашан қойылмайды.**
6. Грубые ошибки бөлек жалауша ретінде көрсетіледі, итог балына әсер етпейді.

```
Транскрипт + Чек-лист v_N ─► PromptBuilder ─► LLM (Gemini) ─► Validator ──ok──► ScoringEngine ─► итог
                                                  ▲               │ қате
                                                  └── 1 retry ◄───┘ ── тағы қате ─► FAILED
```

**Формула:** Иә = толық салмақ, Жоқ = 0, шкала = `салмақ × ступень / max`. N/A критерий есептен шығарылады.
`итог = 100 × Σбалл / Σқолданылатын салмақ`, 2 ондыққа дейін дөңгелектенеді. Қолданылатын критерий болмаса, итог `null`.
Мысал: c6 = 7/10 → 15 × 0.7 = **10.5**. Ал c2 N/A болса, қалған салмақ 85-ке нормаланады.

**Неге сенуге болады:**
- temperature = 0;
- әр қорытынды нақты репликаға сілтейді (UI-де басып тексеруге болады);
- балды детерминді код есептейді;
- 22 JUnit тест бар;
- шешімді әрқашан адам апелляция арқылы өзгерте алады.

**Құны:** бір бағалауға 1 model call кетеді (retry болса 2). Шамамен 3–4 мың input + 1 мың output token. Gemini тегін tier-інде **$0**. Ақылы Flash тарифінде шамамен **$0.003 / бағалау**. Нақты құн әр бағаға token бойынша есептеліп, UI-де `costUsd` болып көрсетіледі.

## 2. Файлдар

| Файл | Не істейді |
|---|---|
| `evaluation/model/*` | CriterionResult, EvaluationResult, CheckedRange (Criterion — `checklist.*`, TranscriptMessage — `conversation.*`) |
| `evaluation/model/DefaultChecklist` + `resources/checklists/default-v1.json` | PDF-тегі 8 критерий |
| `db/migration/V2__seed_default_checklist.sql` | Сол чек-листті БД-ға `default v1` етіп салады |
| `evaluation/scoring/ScoringEngine` | Балл, N/A, итог, апелляция қайта есебі, чек-лист валидациясы |
| `evaluation/ai/PromptBuilder` | Промпт (ережелер + чек-лист + нөмірленген транскрипт) |
| `evaluation/ai/OpenAiCompatibleLlmClient` | Gemini/OpenAI/Groq… HTTP клиенті, 429/5xx кезінде retry |
| `evaluation/ai/MockLlmClient` | Кілт болмаса, тестке арналған баға береді |
| `evaluation/ai/LlmResponseValidator` | Модель жауабын тексереді |
| `evaluation/ai/EvaluationRunner` | Бәрін біріктіреді. №1 осыны шақырады |
| `evaluation/ai/AiConfig` | Spring бин-дері, .env оқу |

## 3. №1 (backend) үшін интеграция

**pom.xml:** Boot 3.5.x-те Jackson 2 бар, ештеңе қосудың қажеті жоқ. Boot 4 қалса, `com.fasterxml.jackson.core:jackson-databind` тәуелділігін қосыңдар.

**Бағалау (`EvaluationService`):**
```java
List<TranscriptMessage> t = mapper.convertValue(conv.getTranscript(), new TypeReference<>() {});
List<Criterion> criteria = version.getCriteria();   // com.cqafrdm.checklist.Criterion — тот же класс, конвертация не нужна
EvaluationOutcome o = evaluationRunner.evaluate(conv.getId(), conv.getChannel(), conv.getLanguage(), t, criteria);
// evaluation: status=o.status(), result=o.result(), total_score=o.totalScore(), gross_error=o.grossError(),
//             model=o.model(), input_tokens=o.inputTokens(), output_tokens=o.outputTokens(),
//             cost_usd=o.costUsd(), error=o.error()
```
`evaluate` бір уақытта ең көбі `LLM_MAX_PARALLEL` (әдепкі 2) сұрау жібереді. Batch үшін оны жай ғана `ExecutorService`-тен шақыра беріңдер.

**Апелляция шешімі (`PATCH /api/appeals/{id}`):**
```java
EvaluationResult base = /* соңғы шешілген апелляцияның final_result-ы, ал ол жоқ болса evaluation.result */;
EvaluationResult fin = scoringEngine.applyOverride(criteria, base, appeal.criterionId,
        "CHANGED".equals(decision) ? newAnswer : currentAnswer, "CHANGED".equals(decision) && newIsNa, reason);
// appeal.final_result = fin; appeal.final_total = fin.totalScore();  evaluation.result өзгермейді
```
`applyOverride` дұрыс емес жауап келсе, `IllegalArgumentException` лақтырады. Оны 400 қатесіне айналдырыңдар.

**Чек-лист сақтау (`POST /api/checklists`):** `scoringEngine.validateChecklist(criteria)`. Қате тізімі бос болмаса, 400 қайтарыңдар.

## 4. `.env` (Gemini)
```
LLM_BASE_URL=https://generativelanguage.googleapis.com/v1beta/openai
LLM_API_KEY=            # aistudio.google.com -> Get API key
LLM_MODEL=gemini-2.5-flash   # AI Studio-дағы соңғы Flash атауын қой
LLM_PRICE_IN_PER_1M=0
LLM_PRICE_OUT_PER_1M=0
LLM_MAX_PARALLEL=2
```
Модельді ауыстыру үшін тек осы 3 жолды өзгертесің (OpenAI: `https://api.openai.com/v1`, Groq: `https://api.groq.com/openai/v1`).

## 5. Тесттер
```
cd backend
./mvnw test -Dtest="ScoringEngineTest,EvaluationRunnerTest"
```
Тексерілетіндер:
- шкала мен салмақ, N/A мен салмақтың қайта бөлінуі, итог `null` болатын жағдай, дөңгелектеу;
- грубая ошибка, апелляция кезінде бастапқы бағаның сақталуы;
- retry мен құнның қосылуы, FAILED;
- ботты дәлел ретінде келтіруге тыйым;
- рұқсатсыз N/A, «Жоқ» үшін checked_range;
- кодты өзгертпей жаңа критерий қосу.

## 6. Дәлдікті арттыру (ең көп ұпай әкеледі)
1. 10 DEV диалогын (RU 5, KZ 3, EN 2) командамен **қолмен** бағалап, кестеге жаз.
2. Сол диалогтарды AI-мен бағала. Критерий бойынша сәйкестік пайызын есепте.
3. Қай критерийде жиі қателессе, сол критерийге `PromptBuilder.SYSTEM` ішінде нақты ереже қос. Әдетте c2 (N/A ма, Жоқ па), c3 (идентификация керек пе) және c4 (шкала) қиын.
4. Осыны 2–3 рет қайтала. Қорғауда: «AI мен адам бағасы X% сәйкес келеді».
