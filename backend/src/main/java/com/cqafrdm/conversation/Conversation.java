package com.cqafrdm.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "conversation")
public class Conversation {

    @Id
    private String id;

    @Column(name = "group_id", nullable = false)
    private String groupId;

    @Column(nullable = false)
    private String channel;

    @Column(nullable = false)
    private String language;

    @Column(name = "audio_url")
    private String audioUrl;

    /** Транскрипт хранится ровно в том виде, в котором получен от организаторов. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private JsonNode transcript;

    @Column(name = "imported_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime importedAt;

    protected Conversation() {}

    public Conversation(String id, String groupId, String channel, String language,
                        String audioUrl, JsonNode transcript) {
        this.id = id;
        this.groupId = groupId;
        this.channel = channel;
        this.language = language;
        this.audioUrl = audioUrl;
        this.transcript = transcript;
    }

    public String getId() { return id; }
    public String getGroupId() { return groupId; }
    public String getChannel() { return channel; }
    public String getLanguage() { return language; }
    public String getAudioUrl() { return audioUrl; }
    public JsonNode getTranscript() { return transcript; }
    public OffsetDateTime getImportedAt() { return importedAt; }

    /** Для upsert при повторном импорте. */
    public void update(String groupId, String channel, String language, String audioUrl, JsonNode transcript) {
        this.groupId = groupId;
        this.channel = channel;
        this.language = language;
        this.audioUrl = audioUrl;
        this.transcript = transcript;
    }
}