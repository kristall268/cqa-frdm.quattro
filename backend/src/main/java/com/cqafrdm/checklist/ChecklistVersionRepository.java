package com.cqafrdm.checklist;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChecklistVersionRepository extends JpaRepository<ChecklistVersion, Long> {

    List<ChecklistVersion> findAllByOrderByCreatedAtDescIdDesc();

    @Query("select coalesce(max(c.version), 0) from ChecklistVersion c where c.name = :name")
    int maxVersion(@Param("name") String name);
}