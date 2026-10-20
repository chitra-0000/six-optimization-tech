package com.bnpp.regliss.importer.six.service;

// TODO: re-add project imports (Alt+Enter / Optimize Imports) for the project classes used below

/**
 * Test helper: builds SixDeliveryService.Delivery objects for tests outside this package
 * (the value classes are final and their setters are package-private).
 */
public final class SixDeliveryFixtures {

    private SixDeliveryFixtures() {
    }

    public static SixDeliveryService.Delivery delivery(Long key) {
        return new SixDeliveryService.Delivery(key);
    }

    /** A file of the delivery waiting at 90% (has a SIX_CONFIDENCE_VALUES row), still in IN. */
    public static SixDeliveryService.DeliveryFile waiting(SixDeliveryService.Delivery d, SixFileKind kind, long exportId,
                                                         Long versionId, String batchNodeId, long jobId) {
        SixDeliveryService.DeliveryFile f = d.file(kind);
        SixDeliveryService.WaitingRow row = new SixDeliveryService.WaitingRow();
        row.exportId = exportId;
        row.jobId = jobId;
        row.key = d.key;
        row.kind = kind;
        row.versionId = versionId;
        row.batchNodeId = batchNodeId;
        f.row = row;
        f.jobId = jobId;
        f.fileInInput = "GSM_" + kind.name() + "_" + d.key + ".xml";
        return f;
    }

    public static SixDeliveryService.DeliveryFile running(SixDeliveryService.Delivery d, SixFileKind kind, long jobId) {
        SixDeliveryService.DeliveryFile f = d.file(kind);
        f.jobOpen = true;
        f.jobId = jobId;
        return f;
    }

    public static SixDeliveryService.DeliveryFile pending(SixDeliveryService.Delivery d, SixFileKind kind) {
        SixDeliveryService.DeliveryFile f = d.file(kind);
        f.fileInInput = "GSM_" + kind.name() + "_" + d.key + ".xml";
        return f;
    }

    /** The file left the IN folder (delivery cancelled by a rollback). */
    public static void leftInput(SixDeliveryService.DeliveryFile f) {
        f.fileInInput = null;
    }
}
