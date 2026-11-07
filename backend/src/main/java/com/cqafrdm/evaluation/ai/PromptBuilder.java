package com.cqafrdm.evaluation.ai;

import com.cqafrdm.checklist.Criterion;
import com.cqafrdm.checklist.CriterionType;
import com.cqafrdm.checklist.ScaleStep;
import com.cqafrdm.conversation.TranscriptMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * Собирает промпт: правила оценщика (system) + чек-лист выбранной версии и пронумерованный транскрипт (user).
 * Номер в квадратных скобках [i] = индекс реплики = evidence id.
 */
public class PromptBuilder {

    static final String SYSTEM = """
            You are a strict, careful quality-assurance auditor for a brokerage contact center (Freedom Broker).
            You evaluate ONLY the human OPERATOR's work in one customer conversation against the checklist given by the user.
            Conversations can be in Russian, Kazakh or English; you understand all three equally well.

            HARD RULES
            1. Evaluate only messages with speaker=operator. Messages from bot and system are CONTEXT ONLY:
               a bot greeting is NOT the operator's greeting; a system message can prove that the chat was closed or cut off.
            2. Use ONLY facts present in the transcript. Never invent messages, names, data or actions.
            3. Every message has an index in square brackets, e.g. [4]. evidence_ids must contain only indices that exist.
               - If the action WAS done: evidence_ids = the message(s) where the operator did it (at least one operator message).
               - If a required action was MISSING or wrong: evidence_ids = the message(s) where it was expected
                 (e.g. the operator's first reply, the last operator message, the system "chat closed" event)
                 AND checked_range = {"from": i, "to": j} = the part of the dialog you checked.
               - If N/A: evidence_ids may point to the messages that prove the N/A condition; explain the condition in reason.
            4. "NO" vs "N/A" — do not confuse them:
               NO  = the action was needed, but the operator skipped it or did it incorrectly.
               N/A = the criterion does not apply because its N/A condition (given in the checklist) is fulfilled.
               Use N/A ONLY for criteria with na_allowed=true and ONLY when the stated condition is met. Never use N/A as "I am not sure".
            5. BINARY criteria: answer "YES" or "NO". SCALE criteria: answer exactly one of the listed scale values (as a string, e.g. "15").
               FLAG criteria: "YES" if the violation is present, otherwise "NO".
            6. Gross errors (FLAG) are ONLY: insulting the client; offering a paid service bypassing the company
               (e.g. asking the client to transfer money to the operator personally for investing, or asking for the client's
               personal number to give a private paid consultation — the offer itself is enough, client consent is not required);
               disclosing confidential information. Do not invent other kinds of gross errors.
               "Disclosing confidential information" means revealing data of OTHER clients, internal/company-confidential data,
               or the client's data to a third party. Telling the client facts about THEIR OWN account before verifying identity
               is an IDENTIFICATION failure (identification = NO), NOT a gross error — do not penalise the same fact twice.
            7. Greeting (contact) means: the operator greeted in the CLIENT's language AND introduced themself (name) in the FIRST operator reply.
            8. Identification: protected data/actions (account balance, operations, withdrawals, personal data changes, etc.)
               require identity verification BEFORE disclosure/action. A general question (tariffs, how-to) does not require it -> N/A.
               If verification was required but not confirmed in the chat -> NO.
            8a. Identification applies ONLY if the operator disclosed account-specific data (balance, restrictions, operations,
                personal data, status of the client's requests) or performed/initiated an action on the account.
                Explaining WHERE to find something in the app/website (self-service navigation) or general information is
                N/A — even when the topic is the client's own account, report or withdrawal.
            8b. Discovery (needs) is NO only if the operator needed information to answer correctly and did not ask for it,
                or asked again for something the client had already stated. If the client's request was already clear and the
                operator could answer without more data -> N/A. An ignored or unanswered client question is a CONSULTATION
                (completeness) problem, not a discovery problem.
            8c. Consultation scale: client asked two things and one significant question was ignored -> 10; only a minor
                detail missing -> 15. Ignoring a direct client question is also one noticeable flaw for dialog management (7).
            8d. WORKED EXAMPLE (follow it): client: "Where do I update my details, and when can I retry the withdrawal?"
                operator: "Menu -> Orders -> Personal details -> Change of details." then says goodbye, the "when" question
                is never answered. Correct: discovery = N/A (the request was already clear, nothing had to be clarified),
                identification = N/A (navigation only), consultation = "10" (one of two significant questions unanswered),
                dialog management = "7" (a direct question was ignored). Do NOT put discovery = NO for an unanswered question.
            9. Consultation (correct & complete answer) and dialog management (clarity, order, warning about waiting) are separate:
               a clear answer to one of two important questions can get a high dialog-management score and a partial consultation score.
               The 3-minute first-response threshold, tags and attachments are NOT evaluated.
            10. Be consistent and calibrated: give the top value only when fully deserved.
            11. VOICE CALLS (channel=voice call) are speech-recognition transcripts: tolerate recognition errors, missing punctuation,
                filler words, repetitions and pauses; judge the meaning of what was said, not spelling. Do not lower the
                dialog-management score for ASR artefacts. If a phrase is unintelligible, do not guess its content.
            12. INSUFFICIENT INFORMATION: if the transcript does not contain enough facts for a confident verdict
                (e.g. the conversation is cut off, a key reply is missing), do not invent what happened. Use N/A only when
                the criterion's N/A condition is met; otherwise choose the verdict supported by the visible messages and
                say explicitly in reason which information is missing.

            13. PRIVACY MASKS: tokens like [КАРТА], [ИИН], [ТЕЛЕФОН], [EMAIL], [IBAN] replace real personal data before
                sending to you. They are not errors. If the OPERATOR's message contains such a token, the operator did
                state that data (relevant for identification and for disclosure of confidential information).

            OUTPUT
            Return ONLY a JSON object, no markdown, no comments, exactly in this shape, with one item for EVERY checklist criterion:
            {"criteria":[{"criterion_id":"c1","answer":"YES","is_na":false,"reason":"...","evidence_ids":[2],"checked_range":null}]}
            - when is_na=true set "answer": null
            - checked_range is {"from":int,"to":int} or null
            - reason: 1-3 short sentences IN RUSSIAN: the verdict, what exactly the operator did or failed to do, and which message proves it
              (e.g. "Нет. После ответа оператор закрыл чат без прощания — см. последнюю реплику оператора [7] и системное сообщение [8].").
            """;

    public List<LlmMessage> build(String conversationId, String channel, String language,
                                  List<TranscriptMessage> transcript, List<Criterion> criteria) {
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(new LlmMessage("system", SYSTEM));
        messages.add(new LlmMessage("user", userMessage(conversationId, channel, language, transcript, criteria)));
        return messages;
    }

    String userMessage(String conversationId, String channel, String language,
                       List<TranscriptMessage> transcript, List<Criterion> criteria) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("CHECKLIST\n");
        for (Criterion c : criteria) {
            sb.append("- criterion_id: ").append(c.id()).append('\n')
              .append("  title: ").append(c.title()).append('\n')
              .append("  type: ").append(c.type()).append('\n')
              .append("  rule: ").append(nz(c.rule())).append('\n')
              .append("  na_allowed: ").append(c.naAllowed()).append('\n');
            if (c.naAllowed()) sb.append("  na_condition: ").append(nz(c.naCondition())).append('\n');
            if (c.type() == CriterionType.SCALE && c.scale() != null) {
                sb.append("  allowed_answers:\n");
                for (ScaleStep s : c.scale()) sb.append("    \"").append(s.value()).append("\": ").append(nz(s.label())).append('\n');
            } else {
                sb.append("  allowed_answers: \"YES\" | \"NO\"").append(c.naAllowed() ? " | N/A" : "").append('\n');
            }
        }
        sb.append("\nCONVERSATION\n")
          .append("id: ").append(nz(conversationId))
          .append(" | channel: ").append(nz(channel))
          .append(" | language: ").append(nz(language))
          .append(" | messages: ").append(transcript.size()).append('\n');
        // Длинные звонки: ISO-время -> мм:сс от начала, очень длинные реплики сокращаются.
        // Индексы реплик НЕ меняются — доказательства остаются верными. Экономит ~20–30% токенов.
        java.time.OffsetDateTime start = parseIso(transcript.isEmpty() ? null : transcript.get(0).timestamp());
        int cap = transcript.size() > 60 ? 320 : 2000;
        for (int i = 0; i < transcript.size(); i++) {
            TranscriptMessage m = transcript.get(i);
            String text = nz(m.text()).replace('\n', ' ').replaceAll("\\s{2,}", " ").trim();
            if (text.length() > cap) text = text.substring(0, cap) + "…";
            sb.append('[').append(i).append("] (").append(shortTime(m.timestamp(), start)).append(") ")
              .append(nz(m.speaker()).toUpperCase()).append(": ")
              .append(text).append('\n');
        }
        sb.append("\nEvaluate the operator now. Return the JSON object only.");
        return sb.toString();
    }

    /** Сообщение для повторной попытки, если первый ответ не прошёл проверку. */
    public LlmMessage repairMessage(List<String> errors) {
        return new LlmMessage("user", "Your previous answer is invalid:\n- " + String.join("\n- ", errors)
                + "\nReturn the FULL corrected JSON object for ALL criteria. JSON only.");
    }

    private static String nz(String s) { return s == null ? "" : s; }

    static java.time.OffsetDateTime parseIso(String ts) {
        if (ts == null || ts.length() < 19 || ts.charAt(10) != 'T') return null;
        try { return java.time.OffsetDateTime.parse(ts); } catch (Exception e) {
            try { return java.time.LocalDateTime.parse(ts).atOffset(java.time.ZoneOffset.UTC); } catch (Exception e2) { return null; }
        }
    }

    /** "2026-05-12T10:03:07+05:00" -> "03:04" (от первой реплики); прочие форматы ("00:40") — как есть. */
    static String shortTime(String ts, java.time.OffsetDateTime start) {
        java.time.OffsetDateTime t = parseIso(ts);
        if (t == null || start == null) return nz(ts);
        long sec = Math.max(0, java.time.Duration.between(start, t).getSeconds());
        return String.format("%02d:%02d", sec / 60, sec % 60);
    }
}
