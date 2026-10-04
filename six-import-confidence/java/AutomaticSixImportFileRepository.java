package com.bnpp.regliss.scheduler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;          // jakarta.annotation.PostConstruct on Spring Boot 3
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.function.Supplier;

import static java.util.stream.Collectors.toList;

@Component
@Slf4j
public class AutomaticSixImportFileRepository {

    @Value("${automatic.import.six.IN.directory}")
    private File inputDirectory;

    @Value("${automatic.import.IN.directory}")
    private File djInputDirectory;
    @Value("${automatic.import.six.IGNORE.directory}")
    private File ignoreDirectory;

    @Value("${automatic.import.six.ERROR.directory}")
    private File errorDirectory;

    @Autowired
    private FileRepository fileRepo;

    @PostConstruct
    public void prepareFolders() {
        fileRepo.makeFolder(inputDirectory);
        fileRepo.makeFolder(ignoreDirectory);
        fileRepo.makeFolder(errorDirectory);
    }

    public List<String> getFilesInInputFolder() {
        List<File> inputFiles = fileRepo.getUnlockedFilesInFolder(inputDirectory);
        return inputFiles.stream().filter(f-> !f.getName().toUpperCase().endsWith(".ZIP")).map(File::getName).collect(toList());
    }

    public void deleteFiles(AutomaticImportFeedBase feed) { feed.getAllFileNames().forEach(this::deleteInputFile); }

    public void moveToErrorDirectory(String fileName) {
        fileRepo.moveFilesToDirectory(fileRepo.getFileByName(inputDirectory, fileName), errorDirectory);
    }

    /**
     * SIX delivery rollback: moves the file from IN to ERROR only if it is still in IN.
     * Unlike moveToErrorDirectory, it never deletes a file already in ERROR when the source is gone
     * (both servers may roll back the same delivery at the same moment).
     *
     * @return true if the file was moved by this call
     */
    public boolean moveToErrorDirectoryIfPresent(String fileName) {
        try {
            return moveIfPresent(fileName, errorDirectory);
        } catch (IOException e) {
            throw new ReglissException(e, "Could not move file: " + fileName + " to " + errorDirectory.getAbsolutePath());
        }
    }

    /**
     * Moves a SIX file of a type that is not integrated (allow.six.file.integration) to IGNORE.
     *
     * The SIX pollers run on several scheduler threads (sched-0..3) and on every server, and each one calls
     * SixImportPoller.processThreeFiles in the same second. So two threads can try to move the same file:
     * the second one used to fail ("Could not move file ... FileNotFoundException") and, worse,
     * FileRepository.moveFilesToDirectory first DELETED the copy already in IGNORE ("Overwriting file")
     * before noticing the source was gone - the ignored file was lost.
     * Now: one atomic rename, a file already moved by another thread is simply skipped, and a real
     * file-system error is logged without stopping the import poller.
     * (void, as before: the only caller, SixImportPoller.processThreeFiles, does not use a result.)
     */
    public void moveToIgnoreDirectoryByFileName(String fileName) {
        try {
            if (moveIfPresent(fileName, ignoreDirectory)) {
                log.info("SIX file {} moved to the ignore folder (type not in allow.six.file.integration)", fileName);
            }
        } catch (IOException e) {
            log.error("Could not move SIX file {} to the ignore folder: {}", fileName, e.getMessage());
        }
    }

    /**
     * One atomic rename IN -> target folder. Never deletes anything in the target folder first; a file that
     * is no longer in IN (moved by another thread or server) returns false.
     */
    private boolean moveIfPresent(String fileName, File targetDirectory) throws IOException {
        File source = fileRepo.getFileByName(inputDirectory, fileName);
        File target = new File(targetDirectory, source.getName()); // NOSONAR: paths are configured not user input
        try {
            // ATOMIC_MOVE = a single rename(): it replaces a same-named file in the target folder in one step
            // and fails with NoSuchFileException if another thread already moved the source.
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (NoSuchFileException alreadyMoved) {
            return false;
        } catch (AtomicMoveNotSupportedException differentFileSystem) {
            // only if the target folder is on another mount: copy + delete (no longer atomic, still safe if the source is gone)
            try {
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                return true;
            } catch (NoSuchFileException alreadyMoved) {
                return false;
            }
        }
    }

    public void moveToErrorDirectory(AutomaticImportFeedBase feed) {
        if(feed.isAuto())
            feed.getAllFileNames().forEach(this::moveToErrorDirectory);
    }

    public File createInputFileByName(String fileName) { return fileRepo.createFileByName(inputDirectory, fileName); }


    public Path getPathForFilename(String fileName) { return Paths.get(inputDirectory.toURI()).resolve(fileName); }

    public File getInputDirectory() { return inputDirectory; }

    public void deleteInputFile(String fileName) { fileRepo.deleteFile(inputDirectory, fileName); }

    public int getFileAgeInSeconds(String fileName) {
        long lastModifiedTime = 0;
        try {
            if(fileName.startsWith("CONVERTER")) {
                lastModifiedTime = Files.getLastModifiedTime(Paths.get(djInputDirectory.getAbsolutePath(), fileName)).toMillis();
            }
            else{
                lastModifiedTime = Files.getLastModifiedTime(Paths.get(inputDirectory.getAbsolutePath(), fileName)).toMillis();
            }
        } catch (IOException e) {
            throw new ReglissException(e,"Could not checklast update time for file "+fileName);
        }
        return (int) ((System.currentTimeMillis() - lastModifiedTime) / 1000);
    }

    public File getInputFileByName(String fileName) { return fileRepo.getFileByName(inputDirectory, fileName); }

    public void createInputFileWithContent(String fileName, InputStream is) throws IOException {
        File targetFile = createInputFileByName(fileName);
        try  {
            Files.copy(is, targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.error("Failed to create file with name {} in IN folder : {}", fileName, e.getMessage());
            throw e;
        }
    }

    public  Supplier<UnicodeBOMInputStream> openFromZipIfNecessary(String fileName){
        return fileRepo.openFromZipIfNecessary(inputDirectory, fileName, true);
    }

}
