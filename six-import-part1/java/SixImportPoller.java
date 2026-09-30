package com.bnpp.regliss.scheduler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

// TODO: re-add project imports (Alt+Enter / Optimize Imports) for:
// ReglissBatchProfile, AutomaticSixImportFileRepository, AutomaticFeedAggregator

@Component
@ReglissBatchProfile
@Slf4j
public class SixImportPoller {

    @Autowired
    private AutomaticSixImportFileRepository automaticSixImportFileRepository;

    @Autowired
    private AutomaticFeedAggregator automaticFeedAggregator;

    @Value("${allow.six.file.integration}")
    private String allowSixFilesToIntegrate;

    @Value("${six.file.prefix}")
    private String sixFileNamePrefix;

    /**
     * Called by the instrument, structure and options pollers. With
     * spring.task.scheduling.pool.size > 1 those pollers now run at the same time on
     * their own threads, so this method is synchronized: two threads must not move the
     * same ignored file at once.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public synchronized boolean processThreeFiles() {
        List<String> unlocked = automaticSixImportFileRepository.getFilesInInputFolder();
        Set<String> processSixFiles = Arrays.stream(allowSixFilesToIntegrate.toUpperCase().split(",")).collect(Collectors.toSet());

        List<String> ignoreFiles = unlocked.stream().filter(f -> f.startsWith(sixFileNamePrefix) && processSixFiles.stream().noneMatch(f::contains)).collect(Collectors.toList());
        if (!ignoreFiles.isEmpty()) {
            for (String fileName : ignoreFiles) {
                try {
                    automaticSixImportFileRepository.moveToIgnoreDirectoryByFileName(fileName);
                } catch (Exception e) {
                    // The other server may have moved it already - not an error for this server.
                    log.warn("Could not move {} to the ignore folder: {}", fileName, e.getMessage());
                }
            }
        }

        List<String> sixFilesReadyToProcess = automaticFeedAggregator.checkAnySixFilesAvailableToBePrecessed(unlocked, processSixFiles);
        return sixFilesReadyToProcess.size() > 1;
    }
}
