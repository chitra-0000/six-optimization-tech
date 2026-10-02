package com.bnpp.regliss.vo;

import java.util.List;

public interface AutomaticImportFeedBase {

    void fillFileName(String fileName);

    boolean hasAllFilesFor(ImportConfiguration configuration);

    String getXmlDataFileName();

    List<String> getAllFileNames();

    List<String> getFileNamesToImport();

    boolean isFullImport();

    AutomaticImportFeedIdBase getFeedId();

    List<AutoFeedFileType> getMissingFiles(ImportConfiguration configuration);

    boolean isDj();

    boolean isCustomAutomatic();

    boolean isSixAutomatic();

    void fillIsAuto(boolean flag);

    boolean isAuto();

    String getOriginalFileName();

    List<ImportLineErrorReport> getErrors();

    void addToErrors(List<ImportLineErrorReport> errors);

    void addToErrors(ImportLineErrorReport error);

}
