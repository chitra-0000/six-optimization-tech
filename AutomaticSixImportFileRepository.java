package com.bnpp.regliss.scheduler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;          // jakarta.annotation.PostConstruct on Spring Boot 3
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
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

    public void moveToIgnoreDirectoryByFileName(String fileName) {
        fileRepo.moveFilesToDirectory(fileRepo.getFileByName(inputDirectory, fileName), ignoreDirectory);
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
