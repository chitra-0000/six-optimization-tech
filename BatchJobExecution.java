package com.bnpp.regliss.entity.batch;

import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.GenericGenerator;

import javax.persistence.*;          // jakarta.persistence.* on Spring Boot 3
import java.time.LocalDateTime;

@Entity
@Table(name = "CTR_BATCH_JOB_EXECUTION")
/**@SequenceGenerator(name = "BatchJobExecutionIdGenerator", sequenceName = "CTR_BATCH_JOB_EXECUTION_SEQ", allocationSize = 20)*/
@GenericGenerator( name = "BatchJobExecutionIdGenerator", strategy = "org.hibernate.id.enhanced.SequenceStyleGenerator",
        parameters = {
                @org.hibernate.annotations.Parameter(name = "sequence_name", value = "CTR_BATCH_JOB_EXECUTION_SEQ") })
public class BatchJobExecution extends AbstractSimpleEntity {
    @Id
    @Getter
    @Setter
    @GeneratedValue( generator = "BatchJobExecutionIdGenerator")
    protected Long id;

    @Getter
    @Setter
    @EqualityField
    @Column(name = "NODE_ID")
    private String nodeId;

    @Getter
    @Setter
    @EqualityField
    @Column(name = "HOST_NAME")
    private String hostName;

    @Getter
    @Setter
    @EqualityField
    @Column(name = "JOB_TYPE")
    @Enumerated(EnumType.STRING)
    private BatchJobType jobType;

    @Getter @Setter
    @EqualityField
    @Column(name = "JOB_PARAMS", length = 2048)
    private String jobParams;

    @Getter @Setter
    @EqualityField
    @Column(name = "PERCENT", columnDefinition = "NUMBER(10,6)")
    private Float percent;

    @Getter @Setter
    @EqualityField
    @Column(name = "START_DATE")
    private LocalDateTime startDate;

    @Getter @Setter
    @EqualityField
    @Column(name = "END_DATE")
    private LocalDateTime endDate;

    @Getter
    @Setter
    @EqualityField
    @Column(name = "LAST_UPDATE_DATE")
    private LocalDateTime lastUpdateDate;

    @Setter
    @Getter
    @EqualityField
    @ManyToOne(fetch = FetchType.LAZY)
    private Version version;

    @Setter
    @Getter
    @Column(name = "REGLISS_LIST_ID")
    private Long reglissListId;

    @Setter
    @Getter
    @Column(name = "UNBLOCKING_USER_ID")
    private Long unblockingUserId;

    public BatchJobExecution() {
    }

    public BatchJobExecution(String nodeId, String hostName, BatchJobType batchJobType, BatchJobParameterLoggable parameters, Version version, Long reglissListId) {
        this(nodeId, hostName, batchJobType, parameters);
        this.version = version;
        this.reglissListId = reglissListId;
    }

    public BatchJobExecution(String nodeId, String hostName, BatchJobType batchJobType, BatchJobParameterLoggable parameters) {
        String jsonParams = JacksonUtil.getSerializedValue(parameters).orElseThrow(() -> new IllegalArgumentException("Could not serialize parameters " + parameters.getPrettyValue()));
        this.nodeId = nodeId;
        this.hostName = hostName;
        this.jobType = batchJobType;
        this.jobParams = jsonParams;
        this.percent = 0f;
        this.startDate = LocalDateTime.now();
        this.lastUpdateDate = LocalDateTime.now();
    }

    public boolean isFinished() {
        return endDate != null;
    }

    public boolean isNotFinished() {
        return !isFinished();
    }

    public void removeVersion() {
        this.version = null;
    }
}
