package com.bnpp.regliss.scheduler;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.function.Supplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// TODO: re-add project imports for: FileRepository, AutomaticImportFeedBase, ReglissException, UnicodeBOMInputStream

public class AutomaticSixImportFileRepositoryTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private AutomaticSixImportFileRepository repo;
    private FileRepository fileRepo;
    private File in;
    private File dj;
    private File ignore;
    private File error;

    @Before
    public void setUp() throws IOException {
        in = tmp.newFolder("in");
        dj = tmp.newFolder("dj");
        ignore = tmp.newFolder("ignore");
        error = tmp.newFolder("error");
        fileRepo = mock(FileRepository.class);
        when(fileRepo.getFileByName(any(File.class), anyString())).thenAnswer(inv -> new File((File) inv.getArgument(0),
                (String) inv.getArgument(1)));
        when(fileRepo.createFileByName(any(File.class), anyString())).thenAnswer(inv -> new File((File) inv.getArgument(0),
                (String) inv.getArgument(1)));
        repo = spy(new AutomaticSixImportFileRepository());
        ReflectionTestUtils.setField(repo, "inputDirectory", in);
        ReflectionTestUtils.setField(repo, "djInputDirectory", dj);
        ReflectionTestUtils.setField(repo, "ignoreDirectory", ignore);
        ReflectionTestUtils.setField(repo, "errorDirectory", error);
        ReflectionTestUtils.setField(repo, "fileRepo", fileRepo);
    }

    private File inFile(String name) throws IOException {
        File f = new File(in, name);
        Files.write(f.toPath(), "x".getBytes(StandardCharsets.UTF_8));
        return f;
    }

    @Test
    public void prepareFolders() {
        repo.prepareFolders();
        verify(fileRepo).makeFolder(in);
        verify(fileRepo).makeFolder(ignore);
        verify(fileRepo).makeFolder(error);
    }

    @Test
    public void filesInInputFolder_zipLeftOut() {
        when(fileRepo.getUnlockedFilesInFolder(in)).thenReturn(Arrays.asList(new File(in, "A.xml"), new File(in, "B.ZIP")));
        assertEquals(Collections.singletonList("A.xml"), repo.getFilesInInputFolder());
    }

    @Test
    public void deleteAndMoveByFeed() {
        AutomaticImportFeedBase feed = mock(AutomaticImportFeedBase.class);
        when(feed.getAllFileNames()).thenReturn(Arrays.asList("a.xml", "b.xml"));
        repo.deleteFiles(feed);
        verify(fileRepo).deleteFile(in, "a.xml");
        verify(fileRepo).deleteFile(in, "b.xml");

        when(feed.isAuto()).thenReturn(false);
        repo.moveToErrorDirectory(feed);
        verify(fileRepo, never()).moveFilesToDirectory(any(), any());
        when(feed.isAuto()).thenReturn(true);
        repo.moveToErrorDirectory(feed);
        verify(fileRepo).moveFilesToDirectory(new File(in, "a.xml"), error);
        verify(fileRepo).moveFilesToDirectory(new File(in, "b.xml"), error);
    }

    @Test
    public void moveToErrorIfPresent() throws IOException {
        inFile("s.xml");
        assertTrue(repo.moveToErrorDirectoryIfPresent("s.xml"));
        assertTrue(new File(error, "s.xml").exists());
        assertFalse(repo.moveToErrorDirectoryIfPresent("s.xml"));     // already moved
        assertTrue(new File(error, "s.xml").exists());                  // the copy in ERROR is kept
    }

    @Test
    public void moveToErrorIfPresent_ioError() throws IOException {
        inFile("e.xml");
        doThrow(new IOException("disk")).when(repo).moveFile(any(Path.class), any(Path.class), any(StandardCopyOption.class));
        try {
            repo.moveToErrorDirectoryIfPresent("e.xml");
            fail();
        } catch (ReglissException e) {
            assertTrue(e.getCause() instanceof IOException);
        }
    }

    @Test
    public void moveToIgnoreByFileName() throws IOException {
        inFile("i.xml");
        repo.moveToIgnoreDirectoryByFileName("i.xml");
        assertTrue(new File(ignore, "i.xml").exists());
        repo.moveToIgnoreDirectoryByFileName("i.xml");                  // gone: skipped, no error
        inFile("j.xml");
        doThrow(new IOException("disk")).when(repo).moveFile(any(Path.class), any(Path.class), any(StandardCopyOption.class));
        repo.moveToIgnoreDirectoryByFileName("j.xml");                  // logged only
        assertTrue(new File(in, "j.xml").exists());
    }

    @Test
    public void moveToIgnoreIfPresent() throws IOException {
        inFile("k.xml");
        assertTrue(repo.moveToIgnoreDirectoryIfPresent("k.xml"));
        assertFalse(repo.moveToIgnoreDirectoryIfPresent("k.xml"));
        inFile("l.xml");
        doThrow(new IOException("disk")).when(repo).moveFile(any(Path.class), any(Path.class), any(StandardCopyOption.class));
        try {
            repo.moveToIgnoreDirectoryIfPresent("l.xml");
            fail();
        } catch (ReglissException e) {
            assertTrue(e.getCause() instanceof IOException);
        }
    }

    @Test
    public void move_otherMount_copyAndDelete() throws IOException {
        inFile("m.xml");
        doThrow(new AtomicMoveNotSupportedException("a", "b", "other mount"))
                .when(repo).moveFile(any(Path.class), any(Path.class), eq(StandardCopyOption.ATOMIC_MOVE));
        doCallRealMethod().when(repo).moveFile(any(Path.class), any(Path.class), eq(StandardCopyOption.REPLACE_EXISTING));
        assertTrue(repo.moveToIgnoreDirectoryIfPresent("m.xml"));
        assertTrue(new File(ignore, "m.xml").exists());
        assertFalse(repo.moveToIgnoreDirectoryIfPresent("m.xml"));     // source gone in the fallback too
    }

    @Test
    public void move_otherMount_sourceGone() throws IOException {
        doThrow(new AtomicMoveNotSupportedException("a", "b", "other mount"))
                .when(repo).moveFile(any(Path.class), any(Path.class), eq(StandardCopyOption.ATOMIC_MOVE));
        doThrow(new NoSuchFileException("gone"))
                .when(repo).moveFile(any(Path.class), any(Path.class), eq(StandardCopyOption.REPLACE_EXISTING));
        assertFalse(repo.moveToErrorDirectoryIfPresent("n.xml"));
    }

    @Test
    public void simpleDelegates() {
        assertEquals(new File(in, "c.xml"), repo.createInputFileByName("c.xml"));
        assertEquals(in.toPath().resolve("p.xml"), repo.getPathForFilename("p.xml"));
        assertSame(in, repo.getInputDirectory());
        repo.deleteInputFile("d.xml");
        verify(fileRepo).deleteFile(in, "d.xml");
        assertEquals(new File(in, "g.xml"), repo.getInputFileByName("g.xml"));
        Supplier<UnicodeBOMInputStream> supplier = () -> null;
        when(fileRepo.openFromZipIfNecessary(in, "z.zip", true)).thenReturn(supplier);
        assertSame(supplier, repo.openFromZipIfNecessary("z.zip"));
    }

    @Test
    public void fileAge() throws IOException {
        File f = inFile("age.xml");
        Files.setLastModifiedTime(f.toPath(), FileTime.fromMillis(System.currentTimeMillis() - 120_000L));
        int age = repo.getFileAgeInSeconds("age.xml");
        assertTrue(String.valueOf(age), age >= 119 && age <= 130);

        File converter = new File(dj, "CONVERTER-x.xml");
        Files.write(converter.toPath(), "x".getBytes(StandardCharsets.UTF_8));
        Files.setLastModifiedTime(converter.toPath(), FileTime.fromMillis(System.currentTimeMillis() - 60_000L));
        int convAge = repo.getFileAgeInSeconds("CONVERTER-x.xml");
        assertTrue(String.valueOf(convAge), convAge >= 59 && convAge <= 70);
    }

    @Test(expected = ReglissException.class)
    public void fileAge_missingFile() {
        repo.getFileAgeInSeconds("missing.xml");
    }

    @Test
    public void createWithContent() throws IOException {
        repo.createInputFileWithContent("new.xml", new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8)));
        assertEquals("abc", new String(Files.readAllBytes(new File(in, "new.xml").toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void createWithContent_ioError() {
        when(fileRepo.createFileByName(in, "bad.xml")).thenReturn(new File(new File(in, "no-such-folder"), "bad.xml"));
        InputStream is = new ByteArrayInputStream(new byte[]{1});
        try {
            repo.createInputFileWithContent("bad.xml", is);
            fail();
        } catch (IOException e) {
            assertFalse(new File(in, "bad.xml").exists());
        }
    }
}
