package com.cqafrdm.evaluation;

import com.cqafrdm.checklist.Criterion;
import com.cqafrdm.evaluation.model.CriterionResult;
import com.cqafrdm.evaluation.model.DefaultChecklist;
import com.cqafrdm.conversation.TranscriptMessage;
import com.cqafrdm.evaluation.scoring.ScoringEngine;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/** Общие тестовые данные: чек-лист по умолчанию и короткий чат. */
public final class TestData {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    private TestData() { }

    public static List<Criterion> checklist() {
        return DefaultChecklist.load(MAPPER);
    }

    public static List<TranscriptMessage> chat() {
        return List.of(
                new TranscriptMessage("00:00", "system", "Чат начат"),
                new TranscriptMessage("00:02", "bot", "Здравствуйте! Я бот. Опишите вопрос."),
                new TranscriptMessage("00:05", "client", "Здравствуйте, не могу вывести деньги со счёта"),
                new TranscriptMessage("00:40", "operator", "Здравствуйте! Меня зовут Айгерим. Подскажите номер счёта и кодовое слово?"),
                new TranscriptMessage("01:10", "client", "12345, кодовое слово Алатау"),
                new TranscriptMessage("02:00", "operator", "Спасибо, личность подтверждена. Вывод в обработке, поступит в течение 1 дня. Чем-то ещё помочь?"),
                new TranscriptMessage("02:20", "client", "Нет, спасибо"),
                new TranscriptMessage("02:25", "operator", "Хорошего дня, до свидания!"),
                new TranscriptMessage("02:30", "system", "Чат закрыт"));
    }

    /** Результат без баллов: answer по каждому критерию c1..c8 (null => N/A). */
    public static List<CriterionResult> answers(String... a) {
        List<Criterion> cl = checklist();
        List<CriterionResult> out = new ArrayList<>();
        for (int i = 0; i < cl.size(); i++) {
            Criterion c = cl.get(i);
            boolean na = a[i] == null;
            out.add(new CriterionResult(c.id(), c.title(), c.type(), ScoringEngine.w(c), a[i], na, null,
                    "test", List.of(3), null));
        }
        return out;
    }
}
