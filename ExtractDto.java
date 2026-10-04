package com.bnpp.regliss.importer.six.extractor;

public class ExtractDto {
    private final Long id;
    private final String isin;

    public ExtractDto(Long id, String isin) {
        this.id = id;
        this.isin = isin;
    }

    public Long getId() { return id; }
    public String getIsin() { return isin; }
}
