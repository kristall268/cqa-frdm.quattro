package com.cqafrdm.conversation;

import com.fasterxml.jackson.databind.JsonNode;

public record ConversationDto(String id, String groupId, String channel, String language,
                              String audioUrl, JsonNode transcript) {

    public static ConversationDto from(Conversation c) {
        return new ConversationDto(c.getId(), c.getGroupId(), c.getChannel(),
                c.getLanguage(), c.getAudioUrl(), c.getTranscript());
    }
}