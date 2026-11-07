package com.cqafrdm.appeal;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppealRepository extends JpaRepository<Appeal, Long> {

    List<Appeal> findByEvaluationIdOrderByCreatedAtAscIdAsc(Long evaluationId);

    List<Appeal> findByStatusOrderByCreatedAtDescIdDesc(String status);
}