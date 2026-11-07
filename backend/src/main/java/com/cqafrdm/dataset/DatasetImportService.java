package com.cqafrdm.dataset;

import com.cqafrdm.conversation.Conversation;
import com.cqafrdm.conversation.ConversationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DatasetImportService {

    private final OrganizerClient client;
    private final ConversationRepository repository;

    public DatasetImportService(OrganizerClient client, ConversationRepository repository) {
        this.client = client;
        this.repository = repository;
    }

    @Transactional
    public ImportResult importGroup(String groupId) {
        OrganizerResponse response = client.fetch(groupId);
        int imported = 0;
        int updated = 0;
        for (OrganizerResponse.Item r : response.data().records()) {
            if (r.conversationId() == null || r.transcript() == null) {
                continue;
            }
            var existing = repository.findById(r.conversationId());
            if (existing.isPresent()) {
                existing.get().update(groupId, r.channel(), r.language(), r.audioUrl(), r.transcript());
                updated++;
            } else {
                repository.save(new Conversation(r.conversationId(), groupId, r.channel(),
                        r.language(), r.audioUrl(), r.transcript()));
                imported++;
            }
        }
        return new ImportResult(groupId, imported, updated, imported + updated);
    }
}