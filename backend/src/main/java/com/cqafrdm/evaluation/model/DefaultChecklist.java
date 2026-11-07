package com.cqafrdm.evaluation.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.List;

/** Начальный чек-лист из CQA_Chat_Quality_Checklist.pdf (resources/checklists/default-v1.json). */
public final class DefaultChecklist {

    public static final String RESOURCE = "/checklists/default-v1.json";

    private DefaultChecklist() { }

    public static List<Criterion> load(ObjectMapper mapper) {
        try (InputStream in = DefaultChecklist.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("Не найден " + RESOURCE);
            return mapper.readValue(in, new TypeReference<List<Criterion>>() { });
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Не удалось прочитать " + RESOURCE, e);
        }
    }
}
