package com.bnpp.regliss.scheduler;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.input.ProxyInputStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static java.nio.file.Files.readAttributes;
import static java.util.stream.Collectors.toList;

@Service
@Slf4j
public class FileRepository {

    public static final String COULD_NOT_LIST_THE_FOLDER = "Could not list the folder: ";
    @Autowired
    private NodeDetailsSupplier nodeDetailsSupplier;

    public void makeFolder(File folder) {
        if (!folder.isDirectory()) {
            boolean ok = folder.mkdirs();
            if (!ok) {
                throw new ReglissException("Directory does not exist and could not be created: " + folder);
            }
            setRightsOnFile(folder);
        }
    }

    public File makeFolder(File parent, String specificFolderName) {
        File folder = new File(parent, org.apache.tika.io.FilenameUtils.normalize(specificFolderName)); // NOSONAR: paths are configured not user input
        makeFolder(folder);
        return folder;
    }

    public List<File> getUnlockedFilesInFolder(File inputDirectory) {
        File[] files = inputDirectory.listFiles();
        if (files == null) {
            throw new IllegalArgumentException(COULD_NOT_LIST_THE_FOLDER + inputDirectory.getAbsolutePath());
        }

        List<File> inputFiles =  Stream.of(files)
                .filter(file -> !file.isDirectory()).filter(this::isNotNfsControlFile)
                .collect(toList());

        List<File> lockedFiles = inputFiles.stream().filter(this::isFileLocked).collect(toList());
        if (!lockedFiles.isEmpty()) {
            log.warn("Ignoring locked files: {}" , lockedFiles);
        }

        inputFiles.removeAll(lockedFiles);
        inputFiles.forEach(this::setRightsOnFile);
        return inputFiles;
    }

    public List<File> getUnlockedFilesFromInFolderAndSubfolders(File inputDirectory) {
        File[] files = inputDirectory.listFiles();
        if (files == null) {
            throw new IllegalArgumentException(COULD_NOT_LIST_THE_FOLDER + inputDirectory.getAbsolutePath());
        }
        List<File> filesFromSubfolders = Stream.of(files)
                .filter(File::isDirectory)
                .map(File::listFiles)
                .flatMap(Stream::of)
                .filter(File::isFile)
                .filter(this::isNotNfsControlFile)
                .collect(toList());

        List<File> inputFiles =  Stream.of(files)
                .filter(file -> !file.isDirectory()).filter(this::isNotNfsControlFile)
                .collect(toList());
        inputFiles.addAll(filesFromSubfolders);

        List<File> lockedFiles = inputFiles.stream().filter(this::isFileLocked).collect(toList());
        if (!lockedFiles.isEmpty()) {
            log.warn("Ignoring locked files: {}" , lockedFiles);
        }

        inputFiles.removeAll(lockedFiles);
        inputFiles.forEach(this::setRightsOnFile);
        return inputFiles;
    }

    public List<File> getDirectoriesInFolder(File inputDirectory) {
        File[] files = inputDirectory.listFiles();
        if (files == null) {
            throw new IllegalArgumentException(COULD_NOT_LIST_THE_FOLDER + inputDirectory.getAbsolutePath());
        }
        List<File> inputFiles =  Stream.of(files)
                .filter(File::isDirectory)
                .collect(toList());

        inputFiles.forEach(this::setRightsOnFile);

        return inputFiles;
    }

    public List<File> getFilesByNames(File inputDirectory, List<String> fileNames) {
        return fileNames.stream().map(fileName -> getFileByName(inputDirectory, FilenameUtils.getName(fileName))).collect(toList());
    }

    public File getFileByName(File inputDirectory, String fileName) {
        return new File(inputDirectory.getAbsolutePath(),org.apache.tika.io.FilenameUtils.normalize(FilenameUtils.getName(fileName)));// NOSONAR: paths are configured not user input
    }

    public File createFileByName(File inputDirectory, String fileName) {
        return new File(inputDirectory.getAbsolutePath(),  org.apache.tika.io.FilenameUtils.normalize(FilenameUtils.getName(fileName)));// NOSONAR: paths are configured not user input
    }

    public void moveFilesToDirectory(File file, File directory) {
        if(file!=null) {
            try {
                File destFile = new File(directory, FilenameUtils.getName(file.getName())); // NOSONAR: paths are configured not user input
                if (destFile.exists()) {
                    log.warn("Overwriting file: {}", destFile);
                    Files.delete(destFile.toPath());
                }
                if(file.exists()) {
                    FileUtils.moveFileToDirectory(file, directory, false);
                    log.debug("Moved file {} to directory {}", file, directory);
                }
            } catch (IOException e) {
                throw new ReglissException(e,
                        "Could not move file: " + file.getAbsolutePath() + " to " + directory.getAbsolutePath());
            }
        }
    }

    private boolean isFileLocked(File file) {
        RandomAccessFile stream = null;
        try { // NOSONAR because stream variable is only used to check if file can be opened; this verbose usage is more explicit
            stream = new RandomAccessFile(file, "rw");
            return false;
        } catch (Exception e) { // NOSONAR because this exception is already logged differently
            log.info("Skipping file {} for this iteration due it's not completely written or no read permission on file", file.getName());
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException e) {  // NOSONAR because this exception is already logged differently
                    log.debug("Exception during closing file {}", file.getName());
                }
            }
        }
        return true;
    }

    private void setRightsOnFile(File file) {
        file.setReadable(true, false);
        file.setWritable(true, false);
    }

    public void deleteFile(File inputDirectory, String fileName) {
        File file = getFileByName(inputDirectory, fileName);
        deleteFile(file);
    }

    private void deleteFile(File file) {
        log.debug("Deleting file {}", file.getName());
        try {
            Files.delete(file.toPath());
        } catch (IOException e) {
            log.error("Could not delete file: {} exception {}", file.getAbsolutePath(), e);
        }
    }

    // FGAT: RUJ00170 : Necessary to open the data files from the ZIP 'on the fly'.
    // Can only be fixed by unzipping the entire 5 GB data files to disk (disk space limitation).
    public UnicodeBOMInputStream getInputStreamByName(File inputDirectory, String fileName) {
        try {
            UnicodeBOMInputStream us = new UnicodeBOMInputStream(new FileInputStream(getFileByName(inputDirectory, fileName))); //NOSONAR cannot use try-with-resources because the re  // TODO: rest of this comment was cut off in the photo - copy it from the IDE
            us.skipBOM();
            return us;
        } catch (IOException e) {
            throw new ReglissException(e);
        }
    }

    public String readFully(File inputDirectory, String fileName) {
        try (UnicodeBOMInputStream is = getInputStreamByName(inputDirectory, fileName)) {
            Reader reader = is.getReader();
            return IOUtils.toString(reader);
        } catch (IOException e) {
            throw new ReglissException(e);
        }
    }

    public String computeMD5HashOfFile(File inputDirectory, String fileName) {
        try (InputStream is = getInputStreamByName(inputDirectory, fileName)) {
            return DigestUtils.md5Hex(is);  // NOSONAR: this is the hash algorithm used by Dow Jones
        } catch (IOException e) {
            throw new ReglissException(e);
        }
    }

    public void deleteFiles(File inputDirectory, Set<String> fileNames) {
        fileNames.forEach(file -> deleteFile(inputDirectory, file));
    }

    public long getFileCreationTimeToMillis(File inputDirectory, String fileName) {
        try {
            return readAttributes(getFileByName(inputDirectory, fileName).toPath(), BasicFileAttributes.class)
                    .creationTime().toMillis();
        } catch (IOException e) {
            throw new ReglissException(e);
        }
    }

    public void deleteFiles(List<File> files) {
        files.forEach(this::deleteFile);
    }

    private boolean isNotNfsControlFile(File file ) {
        return !file.getName().toUpperCase().startsWith(".NFS");
    }

    public Supplier<UnicodeBOMInputStream> openFromZipIfNecessary(File inputFolder, String fileName, boolean withValidation) {
        if (fileName.toUpperCase().endsWith(".ZIP")) {
            return openFromZipAndValidateZipFile(inputFolder, fileName, withValidation);
        } else {
            return () -> getInputStreamByName(inputFolder, fileName);
        }
    }

    private Supplier<UnicodeBOMInputStream> openFromZipAndValidateZipFile(File zipInputFolder, String zipFileName ,boolean withValidation) {
        try {
            Supplier<UnicodeBOMInputStream> unzippedFile = openInputStreamFromZipFile(zipInputFolder, zipFileName, withValidation);
            try (InputStream is = unzippedFile.get()) {
                // TODO remove inner try as outer try catches the exception when opening stream
                // nothing. Just trying to open the zip to addAndTestIfNew the filenames
            }
            log.info("Zip verification OK for file {}", zipFileName);
            return unzippedFile;
        } catch (Exception e) {
            String message = "Error unzipping file: Zipu" + zipFileName + ": " + e.getMessage();
            throw new ReglissException(message, e, ReglissException.ErrorCode.DJ_IMPORT_ZIP_CORRUPT, e.getMessage());
        }
    }

    public Supplier<UnicodeBOMInputStream> openInputStreamFromZipFile(File zipInputFolder, String zipFileName, boolean withValidation) {
        return openInputStreamFromZipFile(getFileByName(zipInputFolder, zipFileName), withValidation);
    }

    public static Supplier<UnicodeBOMInputStream> openInputStreamFromZipFile(File file, boolean withValidation) {
        return () -> {
            try {
                UnicodeBOMInputStream us = new UnicodeBOMInputStream(getInputStreamFromZipFile(file,withValidation));
                us.skipBOM();
                return us;
            } catch (IOException e) {
                throw new ReglissException(e);
            }
        };
    }

    private static InputStream getInputStreamFromZipFile(File file, boolean withValidation) {
        try {
            ZipFile zipFile = new ZipFile(file);
            ZipEntry zipEntry = getFileZipEntry(zipFile);

            InputStream inputStream = zipFile.getInputStream(zipEntry);

            if (withValidation && !FilenameUtils.getBaseName(zipEntry.getName()).equalsIgnoreCase(FilenameUtils.getBaseName(file.getName()))) {
                zipFile.close();
                throw new ReglissException("Invalid name of unzipped file '" + zipEntry.getName() + "' in input zip file '" + file.getName() + "'");
            }
            if (!isExtractedFileExtensionValid(zipEntry)) {
                zipFile.close();
                throw new ReglissException(
                        "Invalid extension of unzipped file in input zip file " + file.getName());
            }

            return new ProxyInputStream(inputStream) {
                @Override
                public void close() throws IOException {
                    super.close();
                    zipFile.close();
                }
            };
        } catch (IOException ioe) {
            throw new ReglissException(ioe);
        }
    }

    @SuppressWarnings("unchecked")
    private static ZipEntry getFileZipEntry(ZipFile zipFile) {
        ZipEntry zipEntry = zipFile.entries().nextElement();
        if (zipEntry.isDirectory()) {
            List<ZipEntry> list = (List<ZipEntry>) Collections.list(zipFile.entries());
            zipEntry = list.get(list.size() - 1);
        }
        return zipEntry;
    }

    private static boolean isExtractedFileExtensionValid(ZipEntry zipEntry) {
        String upperName = zipEntry.getName().toUpperCase();
        return upperName.endsWith(".XML") || upperName.endsWith(".CSV");
    }
}
