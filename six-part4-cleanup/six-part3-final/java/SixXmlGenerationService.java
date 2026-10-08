package com.bnpp.regliss.service.six;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.bind.JAXBException;          // jakarta.xml.bind.* on Spring Boot 3
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// TODO: re-add project imports for: ListType, ExportRepositoryImpl, ImportedFile, ImportedFileRepository,
// BatchJobExecution, BatchJobExecutionRepository, Version, VersionRepository

/**
 * Creates the CONVERTER-...xml file of a SIX output list.
 *
 * Export phase 1: the SIX export no longer puts each file into the DJ IN folder as soon as it is built. The file is
 * written to a HOLDING folder (one sub folder per delivery and list), and all the files of a delivery are moved into
 * DJ IN together when every list of the delivery is finished ({@link #releaseHeldFiles}, called by
 * SixXmlGenerationPoller). So the DJ import does not start on the first files while the export is still running.
 *
 * Holding folder: property six.export.holding.directory; by default the folder "six-export-holding" NEXT TO the DJ IN
 * folder (same mount, seen by both servers, so the move into DJ IN is an atomic rename). It must not be inside DJ IN:
 * FileRepository.getUnlockedFilesFromInFolderAndSubfolders also reads the sub folders of IN. It is created on first use.
 *
 * Every file is written as ".tmp", validated against the XSD, then renamed: neither folder ever shows a half file.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SixXmlGenerationService {

    private static final String FILE_GENERATED_OK = "File generation OK";
    private static final String FILE_GENERATED_KO = "File generation KO";
    private static final String DEFAULT_HOLDING_FOLDER = "six-export-holding";
    private static final String XML_EXTENSION = ".xml";
    private static final String TMP_EXTENSION = ".tmp";
    /** Characters allowed in a holding sub folder name (delivery key, list reference); others become "_" (Fortify). */
    private static final Pattern UNSAFE_FOLDER_CHARS = Pattern.compile("[^A-Za-z0-9_-]");

    @Value("${automatic.import.IN.directory}")
    private File baseTempFolder;

    @Value("${automatic.import.xsd.CUSTOM_AUTOMATIC}")
    public File customAutomaticXsd;

    /** Empty = "six-export-holding" next to the DJ IN folder. */
    @Value("${six.export.holding.directory:}")
    private String holdingDirectory;

    @Autowired
    private ImportedFileRepository importedFileRepository;

    @Autowired
    private BatchJobExecutionRepository batchJobExecutionRepository;

    @Autowired
    private VersionRepository versionRepository;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    public static final Pattern date = Pattern.compile("_(\\d{8}_\\d{6})$$");

    /** Unchanged contract for other callers: "File generation OK" / "File generation KO" (error logged). */
    public String createAndUploadFile(ListType listType, Set<Long> batchJobExecutionIds) {
        log.info(">>>createAndUploadFile :: Process starts to create the XML file....");
        String generatedStatus = FILE_GENERATED_KO;
        try {
            createAndUploadFileOrThrow(listType, batchJobExecutionIds);
            generatedStatus = FILE_GENERATED_OK;
        } catch (Exception e) {
            log.error("Exception in createAndUploadFile method", e);
        }
        log.info(">>>createAndUploadFile :: Process ends and generated status is {}", generatedStatus);
        return generatedStatus;
    }

    /**
     * Same file as createAndUploadFile, put directly into the DJ IN folder, but an error is thrown to the caller (with
     * its real cause: XSD error, disk full, ...) instead of only being logged.
     *
     * @return the name of the file placed in the DJ IN folder
     */
    public String createAndUploadFileOrThrow(ListType listType, Set<Long> batchJobExecutionIds) {
        String fileName = converterFileName(listType, batchJobExecutionIds);
        writeValidatedFile(listType, djInFolder(), fileName);
        return fileName;
    }

    /**
     * SIX export: creates the CONVERTER file of one list in the holding folder of its delivery
     * (holding/&lt;delivery&gt;/&lt;list&gt;/). The file reaches the DJ IN folder only with {@link #releaseHeldFiles}.
     *
     * @return the held file
     */
    public Path createHeldFileOrThrow(ListType listType, Set<Long> batchJobExecutionIds, String delivery, String listRef) {
        String fileName = converterFileName(listType, batchJobExecutionIds);
        Path folder = heldFolder(delivery, listRef);
        try {
            Files.createDirectories(folder);
        } catch (IOException e) {
            throw new IllegalStateException("Holding folder " + folder + " could not be created: " + e.getMessage(), e);
        }
        writeValidatedFile(listType, folder, fileName);
        log.info("File {} held at {} until its delivery {} is complete", fileName, folder, delivery);
        return folder.resolve(fileName);
    }

    /**
     * Moves the held CONVERTER file(s) of one list into the DJ IN folder (atomic rename on the same mount), then removes
     * the empty holding sub folders. A list without held file (already moved by a previous run that stopped before
     * recording it) returns an empty list.
     *
     * @return the names of the files now in the DJ IN folder
     * @throws IOException when a file cannot be moved (it stays in the holding folder)
     */
    public List<String> releaseHeldFiles(String delivery, String listRef) throws IOException {
        Path folder = heldFolder(delivery, listRef);
        if (!Files.isDirectory(folder)) {
            return new ArrayList<>();
        }
        List<Path> held;
        try (Stream<Path> files = Files.list(folder)) {
            held = files.filter(f -> f.getFileName().toString().endsWith(XML_EXTENSION)).sorted().collect(Collectors.toList());
        }
        Path inDir = djInFolder();
        List<String> moved = new ArrayList<>();
        for (Path file : held) {
            String name = file.getFileName().toString();
            moveIntoDjIn(file, inDir, name);
            moved.add(name);
            log.info("File {} moved from {} to {}", name, folder, inDir);
        }
        deleteIfEmpty(folder);
        deleteIfEmpty(folder.getParent());
        return moved;
    }

    /** Holding folder of one list of one delivery, always below the holding root (Fortify: path manipulation). */
    Path heldFolder(String delivery, String listRef) {
        Path root = holdingRoot();
        Path folder = root.resolve(safeName(delivery)).resolve(safeName(listRef)).normalize();
        if (!folder.startsWith(root)) {
            throw new IllegalArgumentException("Invalid holding folder for delivery " + delivery + ", list " + listRef);
        }
        return folder;
    }

    /** The holding root, checked to be outside the DJ IN folder (whose sub folders are read by the DJ import). */
    Path holdingRoot() {
        Path inDir = djInFolder();
        Path root;
        if (holdingDirectory == null || holdingDirectory.trim().isEmpty()) {
            Path parent = inDir.getParent();
            if (parent == null) {
                throw new IllegalStateException("No folder above the DJ IN folder " + inDir + ": set six.export.holding.directory");
            }
            root = parent.resolve(DEFAULT_HOLDING_FOLDER);
        } else {
            root = Paths.get(holdingDirectory.trim()).toAbsolutePath().normalize();
        }
        if (root.startsWith(inDir)) {
            throw new IllegalStateException("six.export.holding.directory " + root + " must not be inside the DJ IN folder " + inDir);
        }
        return root;
    }

    private Path djInFolder() {
        return baseTempFolder.toPath().toAbsolutePath().normalize();
    }

    private static String safeName(String value) {
        String name = value == null ? "" : UNSAFE_FOLDER_CHARS.matcher(value.trim()).replaceAll("_");
        return name.isEmpty() ? "_" : name;
    }

    /**
     * Into DJ IN under its final name in one step: rename (same mount). If the two folders are on different mounts,
     * copy to a ".tmp" in DJ IN, rename it, then delete the held file.
     */
    private static void moveIntoDjIn(Path held, Path inDir, String name) throws IOException {
        Path target = inDir.resolve(name);
        try {
            Files.move(held, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            log.warn("Holding folder and DJ IN folder are on different mounts: {} copied", name);
            Path temp = Files.createTempFile(inDir, name + "_", TMP_EXTENSION);
            try {
                Files.copy(held, temp, StandardCopyOption.REPLACE_EXISTING);
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                Files.delete(held);
            } finally {
                Files.deleteIfExists(temp);
            }
        }
    }

    private static void deleteIfEmpty(Path folder) {
        try {
            if (folder != null) {
                Files.deleteIfExists(folder);
            }
        } catch (DirectoryNotEmptyException e) {
            // other files are still held there
        } catch (IOException e) {
            log.warn("Holding folder {} could not be removed: {}", folder, e.getMessage());
        }
    }

    // ------------------------------------------------------------------------------ part 4: nightly cleanup

    /**
     * CONVERTER files still in the holding folder (holding/&lt;delivery&gt;_&lt;REASON&gt;/&lt;list&gt;/*.xml). Called by the
     * nightly cleanup only when no SIX job works, so every file found here is left over (release failed, server stopped).
     * Links are not followed (Fortify: path manipulation).
     */
    public List<HeldFile> heldFiles() throws IOException {
        Path root = holdingRoot();
        List<HeldFile> held = new ArrayList<>();
        for (Path group : subFolders(root)) {
            for (Path list : subFolders(group)) {
                for (Path file : entries(list)) {
                    if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && file.getFileName().toString().endsWith(XML_EXTENSION)) {
                        LocalDateTime modified = LocalDateTime.ofInstant(
                                Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toInstant(), ZoneId.systemDefault());
                        held.add(new HeldFile(group.getFileName().toString(), list.getFileName().toString(), file, modified));
                    }
                }
            }
        }
        return held;
    }

    /** Moves one left-over held file into the DJ IN folder (same move as the release). */
    public String deliverHeldFile(HeldFile held) throws IOException {
        Path file = checkedHeldPath(held);
        String name = file.getFileName().toString();
        moveIntoDjIn(file, djInFolder(), name);
        log.info("File {} moved from {} to {}", name, file.getParent(), djInFolder());
        return name;
    }

    /** Deletes one left-over held file (older than what DJ already received for its list). */
    public void discardHeldFile(HeldFile held) throws IOException {
        Files.deleteIfExists(checkedHeldPath(held));
    }

    /**
     * Removes the ".tmp" files of builds that stopped (older than {@code tmpAgeMinutes}) and the empty sub folders of
     * the holding folder. The holding folder itself is kept.
     *
     * @return number of ".tmp" files removed
     */
    public int removeTempFilesAndEmptyFolders(long tmpAgeMinutes) throws IOException {
        Path root = holdingRoot();
        long limit = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(tmpAgeMinutes);
        int removed = 0;
        for (Path group : subFolders(root)) {
            for (Path list : subFolders(group)) {
                for (Path file : entries(list)) {
                    if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && file.getFileName().toString().endsWith(TMP_EXTENSION)
                            && Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toMillis() < limit) {
                        Files.deleteIfExists(file);
                        removed++;
                    }
                }
                deleteIfEmpty(list);
            }
            deleteIfEmpty(group);
        }
        return removed;
    }

    /** Holding sub folder name of a delivery group or a list reference (same rule as when the file was held). */
    public static String holdingFolderName(String value) {
        return safeName(value);
    }

    private Path checkedHeldPath(HeldFile held) {
        Path root = holdingRoot();
        Path file = held.getPath().toAbsolutePath().normalize();
        if (!file.startsWith(root) || !file.getFileName().toString().endsWith(XML_EXTENSION)) {
            throw new IllegalArgumentException("Not a held CONVERTER file: " + file);
        }
        return file;
    }

    private static List<Path> subFolders(Path folder) throws IOException {
        List<Path> folders = new ArrayList<>();
        for (Path entry : entries(folder)) {
            if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                folders.add(entry);
            }
        }
        return folders;
    }

    private static List<Path> entries(Path folder) throws IOException {
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {
            return new ArrayList<>();
        }
        try (Stream<Path> entries = Files.list(folder)) {
            return entries.sorted().collect(Collectors.toList());
        }
    }

    /** One CONVERTER file left in the holding folder. */
    public static final class HeldFile {
        private final String group;
        private final String listFolder;
        private final Path path;
        private final LocalDateTime lastModified;

        HeldFile(String group, String listFolder, Path path, LocalDateTime lastModified) {
            this.group = group;
            this.listFolder = listFolder;
            this.path = path;
            this.lastModified = lastModified;
        }

        /** Folder of the delivery group: &lt;delivery&gt;_GENERATION or &lt;delivery&gt;_REGENERATION. */
        public String getGroup() { return group; }
        /** Folder of the list (the list reference). */
        public String getListFolder() { return listFolder; }
        public Path getPath() { return path; }
        public LocalDateTime getLastModified() { return lastModified; }

        @Override
        public String toString() {
            return group + "/" + listFolder + "/" + path.getFileName();
        }
    }

    private String converterFileName(ListType listType, Set<Long> batchJobExecutionIds) {
        List<BatchJobExecution> batchJobExecutionList = batchJobExecutionRepository.findAllById(batchJobExecutionIds);
        boolean regenerationFilter = batchJobExecutionList.stream()
                .map(BatchJobExecution::getJobParams).allMatch(params -> params != null && params.contains("REGENERATION"));
        String dateTimeToFilledInFileName = regenerationFilter ? LocalDateTime.now().format(FMT) : buildApplicationDateFromFileName();
        return "CONVERTER-" + listType.getLabel() + "_" + listType.getSourceVersion() + "_" + dateTimeToFilledInFileName + XML_EXTENSION;
    }

    /** Errors keep their real cause (XSD error, disk full ...); checked ones are wrapped. */
    private void writeValidatedFile(ListType listType, Path folder, String fileName) {
        try {
            writeFile(listType, folder, fileName);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {     // checked errors of the XML writer / XSD validation / file system
            throw new IllegalStateException("CONVERTER file " + fileName + " not created: " + e.getMessage(), e);
        }
    }

    /**
     * Writes the XML straight to a temporary ".tmp" file in the target folder (no copy of the whole XML in memory),
     * validates it, then renames it atomically to its final name. The ".tmp" file is always removed.
     */
    private void writeFile(ListType listType, Path folder, String fileName) throws IOException, SAXException, JAXBException {
        Path target = folder.resolve(fileName);
        Path temp = Files.createTempFile(folder, fileName + "_", TMP_EXTENSION); // same folder -> atomic move
        try {
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(temp, StandardOpenOption.WRITE))) {
                ExportRepositoryImpl.writeFile(listType, out, "test1", "test2", "test3", "test4", "test5");
            }
            validateXsdSchema(temp.toFile());
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            log.info("File {} stored at {}", fileName, target);
        } finally {
            Files.deleteIfExists(temp);
        }
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
