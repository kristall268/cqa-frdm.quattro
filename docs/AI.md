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
5. **Екінші модель (тексеруші)** бағаны тәуелсіз түрде қайта қарап шығады. Келіспеген критерий **⚠ needsReview** деп белгіленіп, адамға жіберіледі. Балдың өзін тексеруші өзгертпейді, оны тек адам апелляция арқылы өзгерте алады. Тексеру сәтсіз болса, баға бәрібір сақталады.
6. Жауап тексеруден өтпесе, модельге қателер тізімімен **1 рет қайта** сұрау жіберіледі. Тағы өтпесе, баға `FAILED` деп белгіленеді. **Ойдан шығарылған балл ешқашан қойылмайды.**
7. Грубые ошибки бөлек жалауша ретінде көрсетіледі, итог балына әсер етпейді.

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

## 3. №1 (backend) үшін интеграция — дайын код

Модуль сенің кластарыңды тікелей қолданады: `checklist.Criterion`, `conversation.TranscriptMessage`, `evaluation.CriterionResult / EvaluationResult / CheckedRange` (BigDecimal). Ешқандай түрлендіру керек емес.
Бин-дер (`EvaluationRunner`, `ScoringEngine`) `AiConfig` арқылы автоматты түрде жасалады.

### 3.1 `POST /api/evaluations` — `EvaluationService`
```java
// inject: ConversationRepository conversations, ChecklistService checklists, EvaluationRunner runner, ObjectMapper mapper
// НЕ ставь @Transactional на весь метод: вызов LLM длится 5–30 с, транзакция держать соединение не должна.
public EvaluationDto evaluate(String conversationId, Long checklistVersionId) {
    Conversation conv = conversations.findById(conversationId)
            .orElseThrow(() -> new NotFoundException("Диалог не найден: " + conversationId));
    ChecklistVersion version = checklists.getEntity(checklistVersionId);
    List<TranscriptMessage> transcript = mapper.convertValue(conv.getTranscript(),
            new TypeReference<List<TranscriptMessage>>() {});

    EvaluationOutcome o = runner.evaluate(conv.getId(), conv.getChannel(), conv.getLanguage(),
            transcript, version.getCriteria());

    Evaluation saved = evaluations.save(new Evaluation(conv.getId(), version.getId(), o.status().name(),
            o.result(), o.model(), o.inputTokens(), o.outputTokens(),
            BigDecimal.valueOf(o.costUsd()), o.error()));
    return toDto(saved);
}
```
`status=FAILED` болса, `result == null`, ал `error` себебін түсіндіреді. Ол 200 қайтарады, UI оны «AI жауабы жарамсыз» деп көрсетеді.

### 3.2 `POST /api/evaluations/batch`
```java
private final ExecutorService pool = Executors.newFixedThreadPool(3);
public int evaluateGroup(String groupId, Long checklistVersionId) {
    List<String> ids = conversations.findByGroupId(groupId).stream().map(Conversation::getId).toList();
    ids.forEach(id -> pool.submit(() -> {
        try { evaluate(id, checklistVersionId); } catch (Exception e) { log.warn("batch {}: {}", id, e.getMessage()); }
    }));
    return ids.size();   // -> {"started": N}
}
```
LLM-ге бір мезетте жіберілетін сұрау саны `LLM_MAX_PARALLEL` (әдепкі 2) мәнімен бәрібір шектеледі. Gemini-дің тегін лимиті үшін мұны 2-ден асырмаған дұрыс.

### 3.3 `PATCH /api/appeals/{id}` — `AppealService.decide`
`Appeal` entity-іне мына метод керек:
```java
public void decide(String decision, String newAnswer, Boolean newIsNa, String reason, EvaluationResult fin) {
    this.status = "DECIDED"; this.decision = decision; this.newAnswer = newAnswer; this.newIsNa = newIsNa;
    this.reviewerReason = reason; this.finalResult = fin;
    this.finalTotal = fin.totalScore(); this.decidedAt = OffsetDateTime.now();
}
```
Сервис:
```java
@Transactional
public AppealDto decide(Long id, AppealDecisionRequest req) {   // {decision, newAnswer, newIsNa, reason}
    Appeal a = appeals.findById(id).orElseThrow(() -> new NotFoundException("Апелляция не найдена: " + id));
    if ("DECIDED".equals(a.getStatus())) throw new ValidationException("Апелляция уже рассмотрена");
    if (req.reason() == null || req.reason().isBlank()) throw new ValidationException("Укажите причину решения");
    Evaluation e = evaluations.findById(a.getEvaluationId()).orElseThrow();
    List<Criterion> criteria = checklists.getEntity(e.getChecklistVersionId()).getCriteria();

    // база = последний final по этой оценке, иначе исходный результат
    EvaluationResult base = appeals.findByEvaluationIdOrderByCreatedAtAscIdAsc(e.getId()).stream()
            .filter(x -> "DECIDED".equals(x.getStatus()) && x.getFinalResult() != null)
            .max(Comparator.comparing(Appeal::getDecidedAt)).map(Appeal::getFinalResult)
            .orElse(e.getResult());

    CriterionResult current = base.criteria().stream()
            .filter(c -> c.criterionId().equals(a.getCriterionId())).findFirst().orElseThrow();
    boolean changed = "CHANGED".equals(req.decision());
    if (!changed && !"CONFIRMED".equals(req.decision())) throw new ValidationException("decision: CONFIRMED | CHANGED");
    String answer = changed ? req.newAnswer() : current.answer();
    boolean isNa = changed ? Boolean.TRUE.equals(req.newIsNa()) : current.isNa();

    EvaluationResult fin;
    try {
        fin = scoringEngine.applyOverride(criteria, base, a.getCriterionId(), answer, isNa, req.reason());
    } catch (IllegalArgumentException ex) {          // недопустимый ответ / N/A запрещён
        throw new ValidationException(ex.getMessage());
    }
    a.decide(req.decision(), changed ? answer : null, changed ? isNa : null, req.reason(), fin);
    return AppealDto.from(a, e.getConversationId());
}
```
`evaluation.result` ешқашан өзгермейді. Бастапқы баға, шешім және финал үшеуі де сақталады.

### 3.4 Чек-лист валидациясы
Сенің `ChecklistService.validate` әдісің жеткілікті. `scoringEngine.validateChecklist(...)` тек `EvaluationRunner` ішінде қосымша тексеру ретінде шақырылады.

## 4. `.env` (Gemini)
```
LLM_BASE_URL=https://generativelanguage.googleapis.com/v1beta/openai
LLM_API_KEY=            # aistudio.google.com -> Get API key
LLM_MODEL=gemini-3.8-flash,gemini-3.5-flash-lite   # үтір арқылы резерв: 503/429/404 болса келесісі
LLM_PRICE_IN_PER_1M=0
LLM_PRICE_OUT_PER_1M=0
LLM_MAX_PARALLEL=2
LLM_VERIFIER_MODEL=gemini-3.8-flash          # тексеруші; бос болса тексеру өшеді
# LLM_VERIFIER_BASE_URL= / LLM_VERIFIER_API_KEY=   # басқа провайдер болса ғана
```
Ең мықты нұсқа: `LLM_MODEL=<Gemini Pro>,gemini-3.8-flash`, ал тексеруші ретінде басқа модель. Pro тегін tier-де жабық болса (404 немесе 429), клиент Flash-қа өзі ауысады.
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
