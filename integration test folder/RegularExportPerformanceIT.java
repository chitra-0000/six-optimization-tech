package com.bnpp.regliss.exporter;

// TODO: imports (lines 3-34) were folded in the IDE; reconstructed from usage.
//       Verify packages of project classes (TestConfig, RealDb, IAutocloseableResources, VersionRepository, Version,
//       GenerateVersionJob, BatchManagementService, GeneratedFile, GeneratedFileBuilder, GeneratedFileFormat,
//       FileExportFormat, FileExportMode, FileExportExtension, BatchExport, BatchExportParams, BatchExportType,
//       AbstractSimpleEntity, User, GenerationReason).
import lombok.extern.slf4j.Slf4j;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.util.List;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;

@RunWith(SpringJUnit4ClassRunner.class)
@TestPropertySource(properties = {
        "hbm2ddl.action=validate", // CRITICAL: Preserve data in DB!!
//        "test.database.url=jdbc:oracle:thin:@I-11672-P.fr.net.intra:1521/I11672BP10",
//        "test.database.username=REG",
//        "test.database.password=ReglissMOE=2017"
        "spring.datasource.url=jdbc:oracle:thin:@//D-11672-P.fr.net.intra:1521/D11672AP10",
        "spring.datasource.username=reglissdev5",
        "spring.datasource.password=regliss=1234"
//        "test.database.url=jdbc:oracle:thin:@D-11672-P.fr.net.intra:1521/D11672BP10",
//        "test.database.username=REG",
//        "test.database.password=Reglissint=2017"
})
@SpringBootTest(classes = TestConfig.class)
@RealDb
@Slf4j
public class RegularExportPerformanceIT {

    private static final String REGLISS_FILE = "regliss-file";

    @Autowired
    private IAutocloseableResources fileHelper;

    @Autowired
    protected VersionRepository versionRepo;

    @Autowired
    private GenerateVersionJob generateVersionJob;

    @Autowired
    private BatchManagementService batchManagementService;

//    @MockBean
//    private MultipartAutoConfiguration multipartAutoConfiguration;

    // TO determine the latest Watchlist versions in DB:
//    select v.id, vdj.dj_filename  from version_dj vdj inner join version v on v.version_dj_id=vdj.id
//    where dj_filename like 'PFA2%' order by v.id desc;
    // To count the number of records in a version:
//    select count(*) from version_record where version_id=520;

    @Before
    public void setup() { initAndHandleMocks(); }

    private void initAndHandleMocks() { MockitoAnnotations.initMocks(this); }

    @Test(expected = Test.None.class)
    public void start() {
        long coreVersionId = 421L;
        Version version = versionRepo.getExactlyOne(coreVersionId);

        GeneratedFile generatedFile = new GeneratedFileBuilder()
                .withGeneratedFileFormat(new GeneratedFileFormat(FileExportFormat.FIRCOSOFT, FileExportMode.FULL, FileExportExtension.txt))
                .withVersion(version)
                .build();

        try {
            File file = fileHelper.createTempFileForThisThread(REGLISS_FILE, ".dat");
            log.info("Writing to file " + file.getAbsolutePath());
            try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(file))){

                BatchExport aBatchExport = aBatchExport(version,
                        Stream.of(generatedFile).map(AbstractSimpleEntity::getId).collect(toList()));
                long batchJobExecId = batchManagementService.createNewExportBatchExecutionInTx(aBatchExport, version);
                aBatchExport.setBatchExecutionId(batchJobExecId);
                generateVersionJob.generateVersionFiles(aBatchExport);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        log.info("Generation done");
    }

    private BatchExport aBatchExport(Version version, List<Long> fileIds) {
        BatchExportParams batchExportParams = new BatchExportParams(BatchExportType.VERSION_GENERATED_FILE, fileIds,
                User.SYS_USERNAME, GenerationReason.GENERATION, version);
        return new BatchExport(batchExportParams);
    }

}
