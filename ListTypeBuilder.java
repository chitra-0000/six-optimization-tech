package com.bnpp.regliss.importer.six.extractor;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ListTypeBuilder {
    @Autowired
    private InstrumentRecordTypeBuilder instrumentRecordTypeBuilder;

    @Autowired
    private StructuredRecordTypeBuilder structuredRecordTypeBuilder;

    @Autowired
    private ImportedFileRepository importedFileRepository;

    @Autowired
    private VersionRepository versionRepository;

    public static final String OTHERS = "OTHERS";
    public static final Pattern date = Pattern.compile("_(\\d{8})_");

    public ListType generationOfListTypes(Map<ReglissList, FilteredFileBundle> filteredFileBundle, String listReference)  {
        ListType listType = new ListType();

        for (Map.Entry<ReglissList, FilteredFileBundle> entry : filteredFileBundle.entrySet()) {
            listType = filteredSixRawToListTypeConverter(entry.getKey(), entry.getValue());
        }

        Map<String, RecordType> externalReferenceMap = listType.getRecords().getRecordObj().stream()
                .collect(Collectors.toMap(RecordType::getExternalReference, recordType -> recordType,
                        (existing, replacement) -> {
                            throw new IllegalStateException(String.format("Duplicate external reference found %s for list ref %s",
                                    existing.getExternalReference(), listReference));
                        }
                ));
        log.info("ExternalReferenceMap size : {}", externalReferenceMap.size());
        return listType;
    }

    public ListType filteredSixRawToListTypeConverter(ReglissList childList, FilteredFileBundle filteredFileBundle) {
        List<RecordType> recordTypes = new ArrayList<>();
        String applicationDateFromFileName = buildApplicationDateFromFileName();
        recordTypes.addAll(instrumentRecordTypeBuilder.filteredInstrumentFileToRecordsTypeConverter(filteredFileBundle.getInstrumentFiles(),
                childList, applicationDateFromFileName));
        recordTypes.addAll(structuredRecordTypeBuilder.filteredStructureFileToRecordsTypeConverter(filteredFileBundle.getStructuredFiles(),
                childList, applicationDateFromFileName));

        RecordsType recordsType = RecordsType.builder().recordObj(recordTypes).build();


        return ListType.builder().applicationDate(applicationDateFromFileName).
                juridictionBase(childList.getScope().name()).label(childList.getShortName()).
                sourceVersion(getSourceVersion(childList)).type("FULL").
                records(recordsType).build();
    }

    private String getSourceVersion(ReglissList childList) {
        return childList.getLastVersion()
                .map(v -> v.getVersionNumber() + 1)
                .map(Object::toString)
                .orElse("1");
    }

    private String buildApplicationDateFromFileName(){
        String match = null;
        Version version = versionRepository.getLatestVersionOfInstruments();
        ImportedFile importedFile = importedFileRepository.findByVersionId(version.getId());
        String nameWithoutExtension = FilenameUtils.getBaseName(importedFile.getOriginalFileName()).toUpperCase();
        Matcher m = date.matcher(nameWithoutExtension);
        if(m.find())
        {
            match=  m.group(1);
        }
        DateTimeFormatter inputFormatter = DateTimeFormatter.ofPattern("yyyyMMdd");
        LocalDate localDate = LocalDate.parse(match, inputFormatter);
        DateTimeFormatter outputFormatter = DateTimeFormatter.ISO_LOCAL_DATE;
        return localDate.format(outputFormatter);
    }
}
