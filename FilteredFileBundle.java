package com.bnpp.regliss.six;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

public class FilteredFileBundle {
    private final List<FilteredInstrumentFile> filteredInstrumentFiles = new ArrayList<>();
    private final List<FilteredStructuredFile> filteredStructuredFiles = new ArrayList<>();


    public List<FilteredInstrumentFile> getInstrumentFiles() {
        return filteredInstrumentFiles;
    }
    public List<FilteredStructuredFile> getStructuredFiles() {
        return filteredStructuredFiles;
    }

    public void addInstrumentFiles(Collection<? extends FilteredInstrumentFile> instruments) {
        Objects.requireNonNull(instruments, "instruments collection must not be null");
        filteredInstrumentFiles.addAll(instruments);
    }

    public void addStructuredFiles(Collection<? extends FilteredStructuredFile> structures) {
        Objects.requireNonNull(structures, "structures collection must not be null");
        filteredStructuredFiles.addAll(structures);
    }
}
