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
            7. Greeting (contact) means: the operator greeted in the CLIENT's language AND introduced themself (name) in the FIRST operator reply.
            8. Identification: protected data/actions (account balance, operations, withdrawals, personal data changes, etc.)
               require identity verification BEFORE disclosure/action. A general question (tariffs, how-to) does not require it -> N/A.
               If verification was required but not confirmed in the chat -> NO.
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
        for (int i = 0; i < transcript.size(); i++) {
            TranscriptMessage m = transcript.get(i);
            sb.append('[').append(i).append("] (").append(nz(m.timestamp())).append(") ")
              .append(nz(m.speaker()).toUpperCase()).append(": ")
              .append(nz(m.text()).replace('\n', ' ')).append('\n');
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
}
