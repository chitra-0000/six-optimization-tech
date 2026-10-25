package com.bnpp.regliss.facade;

// TODO: imports (lines 3-38) were folded in the IDE; reconstructed from usage.
//       Verify packages of project classes (AbstractTransactedRepositoryInMemoryTestBase, AdministrationFacade,
//       CftgtwRequestRepository, CftgtwRequest, UserGuideFileRepository, UserGuideFile, UserGuideDto,
//       ReglissRequestContext, ReglissList, ReglissListBuilder, Version, VersionBuilder, VersionStatus,
//       ImportedFile, ImportConfiguration, ImportFileType).
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.bnpp.regliss.entity.ImportFileType.DOW_JONES_AME;              // TODO verify package
import static com.bnpp.regliss.entity.ImportFileType.DOW_JONES_CORE;
import static com.bnpp.regliss.entity.ImportFileType.DOW_JONES_WATCHLIST;
import static com.bnpp.regliss.entity.ImportFileType.DOW_JONES_WATCHLIST_EXTRA;
import static org.junit.Assert.assertEquals;

@ActiveProfiles("reglissWeb")
public class AdministrationFacadeLocalIT extends AbstractTransactedRepositoryInMemoryTestBase {

    @Autowired
    AdministrationFacade sut;

    @Autowired
    CftgtwRequestRepository cftgtwReqRepo;

    @Autowired
    private UserGuideFileRepository userGuideRepo;

    @Mock
    private ReglissRequestContext requestContext;


    @Before
    public void setUp() {
        cftgtwReqRepo.deleteAll();
        userGuideRepo.deleteAll();
        ReglissList list = new ReglissListBuilder().withShortName("ListName").withAutomaticImport(true).withAutomaticImportConfiguration(aWatchlistImportConfiguration()).build();
        Version version = new VersionBuilder().withStatus(VersionStatus.PUBLISHED_OK).withVersionNumber(1L)
                .withPublicationDate(LocalDateTime.now()).build();
        list.addVersion(version);
        ImportedFile importedFile = new ImportedFile();
        importedFile.setStatus(ImportedFile.UploadStatus.IMPORTED_OK);
        importedFile.setVersion(version);
        importedFile.setUploadType(ImportedFile.UploadType.AUTO);
        importedFile.setUploadDate(LocalDateTime.now());
        importedFile.setFileName("pfa2_201901312200_f.zip");
        importedFile.setList(list);
        em.persist(list);
        em.persist(importedFile);
    }

    private ImportConfiguration aWatchlistImportConfiguration() {
        ImportConfiguration importConfiguration = new ImportConfiguration();
        importConfiguration.setImportFileType(DOW_JONES_WATCHLIST);
        return importConfiguration;
    }

    @Test
    public void getLatestWLImportOKDateTest() {
        LocalDate latestWLImportOKDate = sut.getLatestWLImportOKDate();
        Assert.assertNotNull(latestWLImportOKDate);
        System.out.println(latestWLImportOKDate);
    }

    @Test
    public void requestGtwAsOfDateTest() {
        LocalDateTime reqDateTime = LocalDateTime.now().minusDays(1);
        sut.scheduleCftGtwRequestAsOfDate(reqDateTime);
        commitAndRestartTransaction();
        List<CftgtwRequest> gtwReqs = cftgtwReqRepo.findAll();
        assertEquals(1, gtwReqs.size());
        CftgtwRequest cftgtwRequest = gtwReqs.get(0);
        assertEquals(reqDateTime, cftgtwRequest.getRequestTime());
    }


    @Test
    public void requestNormalGtwTest() {
        LocalDateTime reqDateTime = LocalDateTime.now().minusDays(1);
        sut.scheduleCftGtwRequest();
        commitAndRestartTransaction();
        List<CftgtwRequest> gtwReqs = cftgtwReqRepo.findAll();
        assertEquals(4, gtwReqs.size());
        Set<ImportFileType> fileTypes = gtwReqs.stream().map(CftgtwRequest::getImportFileType).collect(Collectors.toSet());
        Set<ImportFileType> djFileTypes = Stream.of(DOW_JONES_WATCHLIST, DOW_JONES_CORE, DOW_JONES_AME, DOW_JONES_WATCHLIST_EXTRA).collect(Collectors.toSet());
        assertEquals(djFileTypes, fileTypes);
    }

    @Test
    public void uploadUserGuideTest() {
        MultipartFile multipartFile = buildMultipartFile("file1", "userGuide1.pdf", "hello!");
        sut.uploadUserGuide(multipartFile);
        commitAndRestartTransaction();
        List<UserGuideFile> userGuideList = userGuideRepo.findAll();
        assertEquals(1, userGuideList.size());
        UserGuideFile userGuideFile = userGuideList.get(0);
        assertEquals("userGuide1.pdf", userGuideFile.getFileName());

    }

    @Test
    public void getUserGuideFileNameTest(){
        MultipartFile multipartFile = buildMultipartFile("file2", "userGuide2.pdf", "hello!");
        sut.uploadUserGuide(multipartFile);
        commitAndRestartTransaction();
        String fileName = sut.getUserGuideFileName();
        assertEquals("userGuide2.pdf",fileName);
    }

    @Test
    public void getUserGuideTest(){

        MultipartFile multipartFile = buildMultipartFile("file3", "userGuide3.pdf", "hello!");
        sut.uploadUserGuide(multipartFile);
        commitAndRestartTransaction();
        UserGuideDto dto = sut.getUserGuide();
        assertEquals("userGuide3.pdf",dto.fileName);

    }

    @Test
    public void downloadUserGuideFiletest() throws IOException {
        MultipartFile multipartFile = buildMultipartFile("file4", "userGuide4.pdf", "Hello4!");
        sut.uploadUserGuide(multipartFile);
        commitAndRestartTransaction();
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        sut.downloadUserGuideFile(os);

        assertEquals(7,os.size());
    }

    private MultipartFile buildMultipartFile(String name, String originalileName, String content){
        return new MockMultipartFile(
                name,
                originalileName,
                MediaType.APPLICATION_PDF_VALUE,
                content.getBytes());
    }

}
