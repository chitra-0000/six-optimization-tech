package com.bnpp.regliss.facade.dto.six;

import lombok.Getter;

import java.util.List;

public class SixTableFieldNamesDto {

    public SixTableFieldNamesDto(String filterType, List<String> fieldNames) {
        this.filterType = filterType;
        this.fieldNames = fieldNames;
    }

    @Getter
    public String filterType;
    @Getter
    public List<String> fieldNames;
}
