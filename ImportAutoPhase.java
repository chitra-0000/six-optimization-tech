package com.bnpp.regliss.batch.service;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public enum ImportAutoPhase {
    IMPORT_AUTO_VALIDATE_XML_LOG(1) {
        @Override
        public int calculateWeight(ImportConfiguration configuration) {
            if (configuration.getImportFileType() == ImportFileType.DOW_JONES_WATCHLIST) {
                return 1;
            } else {
                return 2;
            }
        }
    },

    IMPORT_AUTO_VALIDATE_XSD_SCHEMA(2) {
        @Override
        public int calculateWeight(ImportConfiguration configuration) {
            int previousPhasesWeight = getPreviousPhasesWeight(configuration);
            if (configuration.getImportFileType() == ImportFileType.DOW_JONES_WATCHLIST) {
                return 2 - previousPhasesWeight;
            } else {
                return 3 - previousPhasesWeight;
            }
        }
    },

    IMPORT_AUTO_XML(3) {
        @Override
        public int calculateWeight(ImportConfiguration configuration) {
            int previousPhasesWeight = getPreviousPhasesWeight(configuration);
            if (configuration.getImportFileType() == ImportFileType.DOW_JONES_WATCHLIST) {
                return 95 - previousPhasesWeight;
            } else if (configuration.getImportFileType().isSixRawFormat()) {
                return 90 - previousPhasesWeight;
            } else {
                return 100 - previousPhasesWeight;
            }
        }
    },

    IMPORT_AUTO_CSV(4) {
        @Override
        public int calculateWeight(ImportConfiguration configuration) {
            if (configuration.getImportFileType() != ImportFileType.DOW_JONES_WATCHLIST) {
                return 0;
            }
            return 100 - getPreviousPhasesWeight(configuration);
        }
    };

    private final int phaseIndex;

    ImportAutoPhase(int phaseIndex) { this.phaseIndex = phaseIndex; }

    public List<ImportAutoPhase> getPreviousPhases(){
        return Stream.of(values()).filter(v -> v.phaseIndex < this.phaseIndex)
                .collect(Collectors.toList());
    }

    public abstract int calculateWeight(ImportConfiguration configuration);

    int getPreviousPhasesWeight(ImportConfiguration configuration) {
        int previousPhasesWeight = 0;
        List<ImportAutoPhase> previousPhases = getPreviousPhases();
        for (ImportAutoPhase phase : previousPhases) {
            if (phase == IMPORT_AUTO_VALIDATE_XML_LOG){
                if(configuration.isLogReception()) {
                    previousPhasesWeight += IMPORT_AUTO_VALIDATE_XML_LOG.calculateWeight(configuration);
                }
            } else {
                previousPhasesWeight += phase.calculateWeight(configuration);
            }
        }
        return previousPhasesWeight;
    }
}
