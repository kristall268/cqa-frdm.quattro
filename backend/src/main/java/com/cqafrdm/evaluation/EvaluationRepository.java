package com.cqafrdm.evaluation;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EvaluationRepository extends JpaRepository<Evaluation, Long> {

    List<Evaluation> findByConversationIdOrderByCreatedAtDescIdDesc(String conversationId);

    Optional<Evaluation> findFirstByConversationIdOrderByCreatedAtDescIdDesc(String conversationId);
}