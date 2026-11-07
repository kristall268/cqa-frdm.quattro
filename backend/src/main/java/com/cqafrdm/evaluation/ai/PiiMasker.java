package com.cqafrdm.evaluation.ai;

import com.cqafrdm.conversation.TranscriptMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Маскирование персональных данных перед отправкой в ОБЛАЧНУЮ модель.
 * Количество и порядок реплик не меняются (индексы доказательств остаются верными),
 * в БД и в интерфейсе хранится исходный транскрипт. Локальной модели данные передаются как есть.
 * Метка сохраняет факт: «оператор назвал номер карты» видно как [КАРТА] — критерий раскрытия
 * конфиденциальной информации по-прежнему оценивается.
 */
public final class PiiMasker {

    private record Rule(Pattern pattern, String label) {}

    private static final List<Rule> RULES = List.of(
            new Rule(Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), "[EMAIL]"),
            new Rule(Pattern.compile("\\bKZ\\d{2}[A-Z0-9]{13,17}\\b", Pattern.CASE_INSENSITIVE), "[IBAN]"),
            new Rule(Pattern.compile("(?<!\\d)(?:\\d{4}[ -]?){3}\\d{4}(?!\\d)"), "[КАРТА]"),
            new Rule(Pattern.compile("(?<!\\d)\\d{12}(?!\\d)"), "[ИИН]"),
            new Rule(Pattern.compile("(?<![\\d+])(?:\\+7|8)[ (-]*7\\d{2}[ )-]*\\d{3}[ -]*\\d{2}[ -]*\\d{2}(?!\\d)"), "[ТЕЛЕФОН]"),
            new Rule(Pattern.compile("(?<![\\d+])\\+\\d[\\d ()-]{9,15}\\d(?!\\d)"), "[ТЕЛЕФОН]"));

    private PiiMasker() {}

    public static String mask(String text) {
        if (text == null || text.isEmpty()) return text;
        String out = text;
        for (Rule r : RULES) out = r.pattern().matcher(out).replaceAll(r.label());
        return out;
    }

    public static List<TranscriptMessage> mask(List<TranscriptMessage> transcript) {
        List<TranscriptMessage> out = new ArrayList<>(transcript.size());
        for (TranscriptMessage m : transcript)
            out.add(new TranscriptMessage(m.timestamp(), m.speaker(), mask(m.text())));
        return out;
    }

    /** Сколько фрагментов замаскировано (для журнала/интерфейса). */
    public static int count(List<TranscriptMessage> original, List<TranscriptMessage> masked) {
        int n = 0;
        for (int i = 0; i < original.size(); i++) {
            String a = original.get(i).text(), b = masked.get(i).text();
            if (a != null && !a.equals(b)) n += b.split("\\[(?:EMAIL|IBAN|КАРТА|ИИН|ТЕЛЕФОН)]", -1).length - 1;
        }
        return n;
    }
}
