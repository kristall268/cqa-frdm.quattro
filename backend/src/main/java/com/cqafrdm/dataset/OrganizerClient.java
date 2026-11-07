package com.cqafrdm.dataset;

import com.cqafrdm.common.ExternalServiceException;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class OrganizerClient {

    private final String baseUrl;
    private final RestClient restClient = RestClient.create();

    public OrganizerClient(@Value("${app.dataset.url:}") String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public OrganizerResponse fetch(String groupId) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new ExternalServiceException("Не задана переменная ORGANIZER_DATASET_URL");
        }
        URI uri = UriComponentsBuilder.fromUriString(baseUrl)
                .replaceQueryParam("group_id", groupId)
                .build(true)
                .toUri();
        OrganizerResponse response;
        try {
            response = restClient.get().uri(uri).retrieve().body(OrganizerResponse.class);
        } catch (Exception e) {
            // сообщение без URL, чтобы подпись не попала в логи и ответы
            throw new ExternalServiceException("Не удалось получить набор от организаторов: "
                    + e.getClass().getSimpleName());
        }
        if (response == null || !response.isSuccess() || response.data() == null
                || response.data().records() == null) {
            String err = response == null ? "пустой ответ" : response.error();
            throw new ExternalServiceException("Организаторы вернули ошибку: " + err);
        }
        return response;
    }
}