package com.bnpp.regliss.entity;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;          // jakarta.persistence.* on Spring Boot 3
import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(name = "SIX_FILTERED_POLLER")
public class SixFilteredPoller extends AbstractSimpleEntity {

    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Id
    @Getter @Setter
    private Long id;

    @Getter  @Setter
    @Column(name = "FILE_TYPE", length = 25)
    private String fileType;

    @Getter @Setter
    @Column(name = "INSERTION_TIME", length = 30)
    private LocalDateTime insertionTime;

    @Getter @Setter
    @Column(name = "RAW_LIST_ID")
    private Long rawListId;

    @Getter @Setter
    @Column(name = "RAW_VERSION_ID")
    private Long rawVersionId;

    @Getter @Setter
    @Column(name = "SIX_LIST_REFERENCE")
    private String sixListReference;

    @Getter @Setter
    @Column(name = "BATCH_JOB_EXECUTION_ID")
    private Long batchJobExecutionId;

    public SixFilteredPoller() {

    }

    public SixFilteredPoller(String fileType,
                             LocalDateTime insertionTime,
                             Long rawListId,
                             Long rawVersionId,
                             String sixListReference,
                             Long batchJobExecutionId) {
        this.fileType = fileType;
        this.insertionTime = insertionTime;
        this.rawListId = rawListId;
        this.rawVersionId = rawVersionId;
        this.sixListReference = sixListReference;
        this.batchJobExecutionId = batchJobExecutionId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SixFilteredPoller)) return false;
        SixFilteredPoller that = (SixFilteredPoller) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }


    @Override
    public String toString() {
        return "SixFilteredPoller{" +
                "id=" + id +
                ", fileType='" + fileType + '\'' +
                ", insertionTime='" + insertionTime + '\'' +
                ", rawListId=" + rawListId +
                ", rawVersionId=" + rawVersionId +
                ", sixListReference=" + sixListReference +
                ", batchJobExecutionId=" + batchJobExecutionId +
                '}';
    }
}
