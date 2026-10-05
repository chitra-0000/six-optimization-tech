    // ==== ReglissListFacade.java - ONLY getSixTablesFieldNames() is changed (getFieldsNames is unchanged) ====
    // Replace the existing method with this one. Extra imports needed (if not already there):
    //   import java.util.LinkedHashSet;
    //   import java.util.TreeMap;

    public List<SixTableFieldNamesDto> getSixTablesFieldNames() {
        List<SixTableFieldNames> tables = new ArrayList<>();
        Class[] classes = {InstrumentFile.class, StructuredFile.class, OptionsFile.class};
        List<String> regimes = sixTargetRepository.findAllDistinctRegimes();
        for(Class<T> clazz : classes) {
            SixTableFieldNames table = new SixTableFieldNames();
            table.setFilterType(CAMEL_SPLIT.matcher(clazz.getSimpleName()).replaceAll(" ").trim());
            table.setFieldNames(getFieldsNames(clazz, regimes));
            tables.add(table);
        }

        // SIX_FIELD_TYPE_FILTER: one entry per filter type, each field name only once, even if the
        // table contains the same FILTER_TYPE / FIELD_NAME pair more than once (rows added by hand,
        // script run twice...). Blank values are ignored; filter types stay sorted, field names keep
        // the order of the table.
        List<SixFieldTypeFilter> sixFieldTypeFilters = sixFieldTypeFilterRepository.findAllByOrderByFilterType();
        Map<String, LinkedHashSet<String>> fieldNamesByFilterType = sixFieldTypeFilters.stream()
                .filter(f -> f.getFilterType() != null && !f.getFilterType().trim().isEmpty())
                .filter(f -> f.getFieldName() != null && !f.getFieldName().trim().isEmpty())
                .collect(Collectors.groupingBy(f -> f.getFilterType().trim(), TreeMap::new,
                        Collectors.mapping(f -> f.getFieldName().trim(), Collectors.toCollection(LinkedHashSet::new))));

        for (Map.Entry<String, LinkedHashSet<String>> sixFieldTypeFilter : fieldNamesByFilterType.entrySet()) {
            SixTableFieldNames table1 = new SixTableFieldNames();
            table1.setFilterType(sixFieldTypeFilter.getKey());
            table1.setFieldNames(new ArrayList<>(sixFieldTypeFilter.getValue()));
            tables.add(table1);
        }
        return tables.stream().map(sixFiltersMapper :: toSixTableFieldNamesDto).collect(toList());
    }
