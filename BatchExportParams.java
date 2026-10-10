package com.bnpp.regliss.entity.batch;

import lombok.Getter;

import javax.persistence.*;          // jakarta.persistence.* on Spring Boot 3
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.stream.Collectors.joining;

@Embeddable
public class BatchExportParams {

    @Getter
    @Column(name = "BATCH_TYPE", nullable=false)
    @Enumerated(EnumType.STRING)
    private BatchExportType exportType;

    @Column(name = "GENERATED_FILE_IDS", nullable=false, length = 2048)
    private String generatedFileIds;

    @Getter
    @Column(name = "LAUNCHER_USERNAME", nullable=false)
    private String launcherUsername;

    @Getter
    @Column(name = "GENERATION_REASON", nullable=false)
    @Enumerated(EnumType.STRING)
    private GenerationReason reason;

    @ManyToOne
    @JoinColumn(name = "VERSION_ID")
    private Version version;

    public BatchExportParams() {}

    public BatchExportParams(BatchExportType batchType, List<Long> generatedFileIds, String launcherUsername, GenerationReason reason, Version version) {
        this(batchType, generatedFileIds, launcherUsername, reason);
        this.version = version;
    }

    public BatchExportParams(BatchExportType batchType, List<Long> generatedFileIds, String launcherUsername, GenerationReason reason) {
        this.exportType = batchType;
        this.launcherUsername = launcherUsername;
        this.reason = reason;
        setGeneratedFileIds(generatedFileIds);
    }

    public List<Long> getGeneratedFileIds() {
        return Stream.of(generatedFileIds.split(","))
                .map(Long::parseLong)
                .collect(Collectors.toList());
    }

    private void setGeneratedFileIds(List<Long> generatedFileIds) {
        this.generatedFileIds = generatedFileIds.stream()
                .map(String::valueOf)
                .collect(joining(","));
    }

    public Optional<Version> getVersionOpt() {
        return Optional.ofNullable(version);
    }

    @Override
    public String toString() {
        return "BatchExportParams{" +
                "batchType = " + exportType +
                ", generatedFileIds = '" + generatedFileIds + '\'' +
                ", launcherUsername = '" + launcherUsername + '\'' +
                ", reason = " + reason +
                getVersionOpt().map(v -> ", versionId = " + v.getId()).orElse(", no version present") +
                '}';
    }
}
