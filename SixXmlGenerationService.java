package com.bnpp.regliss.service.six;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class SixXmlGenerationService {

    private static final String FILE_GENERATED_OK = "File generation OK";
    private static final String FILE_GENERATED_KO = "File generation KO";

    @Value("${automatic.import.IN.directory}")
    private File baseTempFolder;

    @Value("${automatic.import.xsd.CUSTOM_AUTOMATIC}")
    public File customAutomaticXsd;

    @Autowired
    private ImportedFileRepository importedFileRepository;

    @Autowired
    private BatchJobExecutionRepository batchJobExecutionRepository;

    @Autowired
    private VersionRepository versionRepository;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    public static final Pattern date = Pattern.compile("_(\\d{8}_\\d{6})$$");

    public String createAndUploadFile(ListType listType, Set<Long> batchJobExecutionIds) {
        log.info(">>>createAndUploadFile :: Process starts to create the XML file....");

        List<BatchJobExecution> batchJobExecutionList = batchJobExecutionRepository.findAllById(batchJobExecutionIds);
        boolean regenerationFilter = batchJobExecutionList.stream().map(BatchJobExecution::getJobParams).allMatch(params ->
                params.contains("REGENERATION"));
        String dateTimeToFilledInFileName = "";
        if (regenerationFilter) {
            dateTimeToFilledInFileName = LocalDateTime.now().format(FMT);
        } else {
            dateTimeToFilledInFileName = buildApplicationDateFromFileName();
        }

        String generatedStatus = FILE_GENERATED_KO;
        try {
            String fileName = "CONVERTER-"+listType.getLabel() + "_" + listType.getSourceVersion() + "_" + dateTimeToFilledInFileName + ".xml";
            InputStream inputStream = ExportRepositoryImpl.createFile(listType, "test1", "test2", "test3",
                    "test4", "test5");
            uploadFile(inputStream, fileName);
            generatedStatus = FILE_GENERATED_OK;
        } catch (Exception e) {
            log.error("Exception in createAndUploadFile method", e);
        }

        log.info(">>>createAndUploadFile :: Process ends and generated status is {}", generatedStatus);
        return generatedStatus;
    }

    private void uploadFile(InputStream inputStream, String fileName) throws IOException, SAXException {
        Path inDir = baseTempFolder.toPath().toAbsolutePath();
        Path temp = Files.createTempFile(baseTempFolder.toPath(), fileName + "_", ".xml"); // same FS
        Path target = inDir.resolve(fileName);
        try (OutputStream out = Files.newOutputStream(temp, StandardOpenOption.WRITE)) {
            IOUtils.copy(inputStream, out);
        }
        validateXsdSchema(temp.toFile());
        Files.move(temp, target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);

        log.info("File {} stored at {}", fileName, target);
        Files.deleteIfExists(temp);
    }
    private void validateXsdSchema(File xml) throws SAXException, IOException {
        SchemaFactory sf = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        sf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Schema schema = sf.newSchema(customAutomaticXsd);
        Validator validator = schema.newValidator();
        validator.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        validator.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        validator.validate(new StreamSource(xml));
    }

    private String buildApplicationDateFromFileName(){
        String match = "";
        Version version = versionRepository.getLatestVersionOfInstruments();
        ImportedFile importedFile = importedFileRepository.findByVersionId(version.getId());
        String nameWithoutExtension = FilenameUtils.getBaseName(importedFile.getOriginalFileName()).toUpperCase();
        Matcher m = date.matcher(nameWithoutExtension);
        if(m.find())
        {
            match=  m.group(1);
        }
        return match;
    }
}
