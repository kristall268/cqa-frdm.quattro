package com.cqafrdm.appeal;

import com.cqafrdm.evaluation.EvaluationResult;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "appeal")
public class Appeal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "evaluation_id", nullable = false)
    private Long evaluationId;

    @Column(name = "criterion_id", nullable = false)
    private String criterionId;

    @Column(nullable = false)
    private String comment;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_ids", columnDefinition = "jsonb")
    private List<Integer> evidenceIds;

    @Column(nullable = false)
    private String status = "OPEN"; // OPEN | DECIDED

    private String decision; // CONFIRMED | CHANGED

    @Column(name = "new_answer")
    private String newAnswer;

    @Column(name = "new_is_na")
    private Boolean newIsNa;

    @Column(name = "reviewer_reason")
    private String reviewerReason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "final_result", columnDefinition = "jsonb")
    private EvaluationResult finalResult;

    @Column(name = "final_total")
    private BigDecimal finalTotal;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "decided_at")
    private OffsetDateTime decidedAt;

    protected Appeal() {}

    public Appeal(Long evaluationId, String criterionId, String comment, List<Integer> evidenceIds) {
        this.evaluationId = evaluationId;
        this.criterionId = criterionId;
        this.comment = comment;
        this.evidenceIds = evidenceIds;
        this.createdAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }
    public Long getEvaluationId() { return evaluationId; }
    public String getCriterionId() { return criterionId; }
    public String getComment() { return comment; }
    public List<Integer> getEvidenceIds() { return evidenceIds; }
    public String getStatus() { return status; }
    public String getDecision() { return decision; }
    public String getNewAnswer() { return newAnswer; }
    public Boolean getNewIsNa() { return newIsNa; }
    public String getReviewerReason() { return reviewerReason; }
    public EvaluationResult getFinalResult() { return finalResult; }
    public BigDecimal getFinalTotal() { return finalTotal; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getDecidedAt() { return decidedAt; }
    public void decide(String decision, String newAnswer, Boolean newIsNa, String reason, EvaluationResult fin) {
        this.status = "DECIDED";
        this.decision = decision;
        this.newAnswer = newAnswer;
        this.newIsNa = newIsNa;
        this.reviewerReason = reason;
        this.finalResult = fin;
        this.finalTotal = fin.totalScore();
        this.decidedAt = OffsetDateTime.now();
    }
}