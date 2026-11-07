package com.cqafrdm.evaluation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "evaluation")
public class Evaluation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", nullable = false)
    private String conversationId;

    @Column(name = "checklist_version_id", nullable = false)
    private Long checklistVersionId;

    @Column(nullable = false)
    private String status; // OK | FAILED

    /** Исходный результат AI. Никогда не перезаписывается. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private EvaluationResult result;

    @Column(name = "total_score")
    private BigDecimal totalScore;

    @Column(name = "gross_error")
    private Boolean grossError;

    private String model;

    @Column(name = "input_tokens")
    private Integer inputTokens;

    @Column(name = "output_tokens")
    private Integer outputTokens;

    @Column(name = "cost_usd")
    private BigDecimal costUsd;

    private String error;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected Evaluation() {}

    public Evaluation(String conversationId, Long checklistVersionId, String status,
                      EvaluationResult result, String model, Integer inputTokens,
                      Integer outputTokens, BigDecimal costUsd, String error) {
        this.conversationId = conversationId;
        this.checklistVersionId = checklistVersionId;
        this.status = status;
        this.result = result;
        this.totalScore = result == null ? null : result.totalScore();
        this.grossError = result == null ? null : result.grossError();
        this.model = model;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.costUsd = costUsd;
        this.error = error;
        this.createdAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }
    public String getConversationId() { return conversationId; }
    public Long getChecklistVersionId() { return checklistVersionId; }
    public String getStatus() { return status; }
    public EvaluationResult getResult() { return result; }
    public BigDecimal getTotalScore() { return totalScore; }
    public Boolean getGrossError() { return grossError; }
    public String getModel() { return model; }
    public Integer getInputTokens() { return inputTokens; }
    public Integer getOutputTokens() { return outputTokens; }
    public BigDecimal getCostUsd() { return costUsd; }
    public String getError() { return error; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}