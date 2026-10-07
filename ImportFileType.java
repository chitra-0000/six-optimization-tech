package com.bnpp.regliss.entity;

import java.util.List;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;

public enum ImportFileType {

    DOW_JONES_CORE {
        @Override
        public List<DJFileImportMode> djFileImportModes() {
            return asList(DJFileImportMode.values());
        }
    },
    DOW_JONES_WATCHLIST {
        @Override
        public List<DJFileImportMode> djFileImportModes() {
            return asList(DJFileImportMode.values());
        }
    },
    DOW_JONES_WATCHLIST_EXTRA {
        @Override
        public List<DJFileImportMode> djFileImportModes() {
            return asList(DJFileImportMode.values());
        }
    },
    DOW_JONES_AME {
        @Override
        public List<DJFileImportMode> djFileImportModes() {
            return asList(DJFileImportMode.values());
        }
    },
    CRB_LIST {
        @Override
        public List<DJFileImportMode> djFileImportModes() {
            return emptyList();
        }
    },
    SIX_STRUCTURED_FILE {
        @Override
        public List<DJFileImportMode> djFileImportModes() {
            return emptyList();
        }
    },
    SIX_INSTRUMENTS_FILE {
        @Override
        public List<DJFileImportMode> djFileImportModes() {
            return emptyList();
        }
    },
    SIX_OPTIONS_FILE {
        @Override
        public List<DJFileImportMode> djFileImportModes() {
            return emptyList();
        }
    },
    SIX_MAIN_FILE {
        @Override
        public List<DJFileImportMode> djFileImportModes() {
            return emptyList();
        }
    },
    SIX_LIST {
        @Override
        public List<DJFileImportMode> djFileImportModes() {
            return asList(DJFileImportMode.values());
        }
    },
    SIX_LIST_ARCHIVE {
        @Override
        public List<DJFileImportMode> djFileImportModes() {
            return asList(DJFileImportMode.values());
        }
    },

    CUSTOM {
        @Override
        public List<DJFileImportMode> djFileImportModes() {
            return emptyList();
        }
    },
    MANUAL_PROCESSING {
        @Override
        public List<DJFileImportMode> djFileImportModes() {
            return emptyList();
        }
    },
    NOT_SUPPORTED {
        @Override
        public List<DJFileImportMode> djFileImportModes() {
            return emptyList();
        }
    },
    UNKNOWN {
        @Override
        public List<DJFileImportMode> djFileImportModes() {
            return emptyList();
        }
    };

    public boolean isDjFormat() {
        return this == DOW_JONES_WATCHLIST ||
                this == DOW_JONES_AME ||
                this == DOW_JONES_WATCHLIST_EXTRA ||
                this == DOW_JONES_CORE;
    }

    public boolean isSixRawFormat() {
        return this == SIX_STRUCTURED_FILE ||
                this == SIX_INSTRUMENTS_FILE || this == SIX_OPTIONS_FILE;
    }

    public boolean isCRBListFileType() { return this == CRB_LIST; }

    public boolean isSIXStructuredFileType() { return this == SIX_STRUCTURED_FILE; }

    public boolean isSIXINSTRUMENTSFileType() { return this == SIX_INSTRUMENTS_FILE; }

    public boolean isSIXOptionsFileType() { return this == SIX_OPTIONS_FILE; }

    public boolean isSIXMainFileType() { return this == SIX_MAIN_FILE; }

    public boolean isCustomFileType() { return this == CUSTOM; }

    public boolean isWatchlistFormat() { return this == DOW_JONES_WATCHLIST; }

    public boolean isAmeFormat() { return this == DOW_JONES_AME; }

    public abstract List<DJFileImportMode> djFileImportModes();

    public boolean isCoreFormat() { return this == DOW_JONES_CORE; }

    public boolean isExtraWatchlistFormat() { return this == DOW_JONES_WATCHLIST_EXTRA; }

    public boolean isManualProcessingFormat() { return this == MANUAL_PROCESSING; }

    public boolean isNotSupportedFormat(){return this == NOT_SUPPORTED; }

}
