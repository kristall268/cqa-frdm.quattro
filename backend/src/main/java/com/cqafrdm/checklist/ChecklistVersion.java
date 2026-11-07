package com.cqafrdm.checklist;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "checklist_version")
public class ChecklistVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private int version;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<Criterion> criteria;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected ChecklistVersion() {}

    public ChecklistVersion(String name, int version, List<Criterion> criteria) {
        this.name = name;
        this.version = version;
        this.criteria = List.copyOf(criteria);
        this.createdAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public int getVersion() { return version; }
    public List<Criterion> getCriteria() { return criteria; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}