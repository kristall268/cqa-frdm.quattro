package com.cqafrdm.dataset;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OrganizerResponse(String error,
                                @JsonProperty("isSuccess") boolean isSuccess,
                                Data data) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Data(@JsonProperty("dataset_group_id") String datasetGroupId,
                       @JsonProperty("total_records") int totalRecords,
                       List<Item> records) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(@JsonProperty("conversation_id") String conversationId,
                       String channel,
                       String language,
                       JsonNode transcript,
                       @JsonProperty("audio_url") String audioUrl) {}
}