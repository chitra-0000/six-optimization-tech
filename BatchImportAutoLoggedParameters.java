package com.bnpp.regliss.vo;

import lombok.Data;

import java.util.List;

/**
 * This class is used to serialize the parameters of the automatic import process
 * and log them in the database in the column JOB_PARAMS of the table CTR_BATCH_JOB_EXECUTION.
 * WARNING: The data in the column JOB_PARAMS of the table CTR_BATCH_JOB_EXECUTION is later deserialized to an object of this type,
 * so any changes performed on current class may also affect the deserialization process for older parameters serialized from previous versions of this class.
 */
@Data
public class BatchImportAutoLoggedParameters implements BatchJobParameterLoggable {
    private static final long serialVersionUID = 1L;
    private List<String> filenames;
    private long listId;

    //used by JACKSON DONT DELETE IT!!!
    public BatchImportAutoLoggedParameters() {}

    public BatchImportAutoLoggedParameters(Long listId, AutomaticImportFeedBase feed) {
        this.listId = listId;
        this.filenames = feed.getAllFileNames();
    }

    @Override
    public String getPrettyValue() {
        return "Import for list id: " + listId
            + "; " + "Filenames: " + filenames;

    }

}
