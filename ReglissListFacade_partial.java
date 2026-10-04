    // ==== ReglissListFacade.java - ONLY these two methods were in the photos (about lines 930-962 of the class) ====
    // Paste them inside the existing ReglissListFacade class. They need these imports if not already there:
    //   import javax.persistence.Column;              (jakarta.persistence.Column on Spring Boot 3)
    //   import java.util.ArrayList; java.util.Arrays; java.util.List; java.util.Map;
    //   import java.util.stream.Collectors;
    //   import static java.util.stream.Collectors.toList;

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
        List<SixFieldTypeFilter> sixFieldTypeFilters = sixFieldTypeFilterRepository.findAllByOrderByFilterType();
        Map<String, List<String>> fieldNamesByFilterType =  sixFieldTypeFilters.stream().collect(Collectors.groupingBy
                (SixFieldTypeFilter::getFilterType, Collectors.mapping(SixFieldTypeFilter::getFieldName, Collectors.toList())));
        for (Map.Entry<String, List<String>> sixFieldTypeFilter : fieldNamesByFilterType.entrySet()) {
            SixTableFieldNames table1 = new SixTableFieldNames();
            table1.setFilterType(sixFieldTypeFilter.getKey());
            table1.setFieldNames(sixFieldTypeFilter.getValue());
            tables.add(table1);
        }
        return tables.stream().map(sixFiltersMapper :: toSixTableFieldNamesDto).collect(toList());
    }

    public List<String> getFieldsNames(Class<T> clazz, List<String> regimes) {
        List<String> fieldNames = Arrays.stream(clazz.getDeclaredFields())
                .filter(f -> f.isAnnotationPresent(Column.class))
                .map(f -> f.getAnnotation(Column.class).name()).filter(f -> !f.equals("ID") && !f.equals("LIST_ID") &&
                        !f.equals("VERSION_ID") && !f.equals("REGIMES"))
                .collect(Collectors.toList());

        List<String> sixTargetFieldNames = Arrays.stream(SixTarget.class.getDeclaredFields())
                .filter(f -> f.isAnnotationPresent(Column.class))
                .map(f -> f.getAnnotation(Column.class).name()).filter(f -> !f.equals("ID") && !f.equals("SIX_INSTRU_ID") &&
                        !f.equals("SIX_STRUCT_ID") && !f.equals("SIX_OPT_ID") && !f.equals("REGIME"))
                .collect(Collectors.toList());

        fieldNames.addAll(regimes.stream().map(regime -> regime + " - REGIME").collect(toList()));
        fieldNames.addAll(sixTargetFieldNames);
        return fieldNames.stream().sorted().collect(toList());
    }
