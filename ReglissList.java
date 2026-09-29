package com.bnpp.regliss.entity;

import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.GenericGenerator;

import javax.persistence.*;          // use jakarta.persistence.* if the project is on Spring Boot 3
import java.time.LocalDateTime;
import java.util.*;

import static java.util.Collections.*;
import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.toSet;

// TODO: the project imports were collapsed ("import ...") in the screenshots.
// Re-add them in the IDE (Alt+Enter / Optimize Imports) for:
// AbstractSimpleEntity, AuditedField, AuditedFieldCode, AuditedCollection, AuditedAction, EqualityField,
// Scope, ReglissListType, Confidentiality, ListCategory, Country, Issuer, RegulatorOrInternalPolicy,
// ImportConfiguration, GeographicData, Version, DJExportFilter, BroadcastEmail, MappingFile, Subscription,
// ConcatenatedList, SixFilters, StructuredFile, InstrumentFile, OptionsFile, ImportFileType,
// FileExportFormat, DJFileImportMode

@Entity
@Table(name = "REGLISS_LIST",
        indexes = {@Index(columnList = "CONFIDENTIALITY"),@Index(columnList = "SCOPE")})
/**@SequenceGenerator(name = "ReglissListIdGenerator", initialValue = 100, sequenceName = "LIST_SEQ", allocationSize = 1)*/
@GenericGenerator( name = "ReglissListIdGenerator", strategy = "org.hibernate.id.enhanced.SequenceStyleGenerator",
        parameters = {
                @org.hibernate.annotations.Parameter(name = "sequence_name", value = "LIST_SEQ")} )

public class ReglissList extends AbstractSimpleEntity {

    @Id
    @Getter
    @Setter
    @GeneratedValue(generator = "ReglissListIdGenerator")
    protected Long id;
    @Getter
    @AuditedField(AuditedFieldCode.LIST_NAME)
    @Column(name = "NAME",  length = 256) // , nullable = false
    private String name;

    @Column(name = "NAME_NORMALIZED",  length = 384)
    private String nameNormalized;

    @Getter
    @AuditedField(AuditedFieldCode.LIST_SHORT_NAME)
    @Column(name = "SHORT_NAME", length = 20) // , nullable = false
    private String shortName;

    @Column(name = "SHORT_NAME_NORMALIZED", length = 30)
    private String shortNameNormalized;

    @Getter @Setter
    @Column(name = "IS_ACTIVE")//, nullable = false)
    private boolean active;

    @Getter @Setter
    @AuditedField(AuditedFieldCode.LIST_DESCRIPTION)
    @Column(name = "DESCRIPTION", length = 1000)//, nullable = false)
    private String description;

    @Getter @Setter
    @EqualityField
    @AuditedField(AuditedFieldCode.LIST_REFERENCE)
    @Column(name = "REFERENCE", length = 3)//, nullable = false)
    private String reference;

    @Getter @Setter
    @AuditedField(AuditedFieldCode.LIST_COMMENT_EN)
    @Column(name = "COMMENT_EN", length = 4000)
    private String commentEn;

    @Getter @Setter
    @AuditedField(AuditedFieldCode.LIST_COMMENT_FR)
    @Column(name = "COMMENT_FR", length = 4000)
    private String commentFr;

    @Getter @Setter
    @AuditedField(AuditedFieldCode.LIST_SCOPE)
    @Enumerated(EnumType.STRING)
    private Scope scope;

    @Getter @Setter
    @AuditedField(AuditedFieldCode.LIST_TYPE)
    @Column(name = "LIST_TYPE")//, nullable = false)
    @Enumerated(EnumType.STRING)
    private ReglissListType type;

    @Getter @Setter
    @AuditedField(AuditedFieldCode.LIST_CONFIDENTIALITY)
    @Enumerated(EnumType.STRING)
    private Confidentiality confidentiality;

    @Getter @Setter
    @AuditedField(AuditedFieldCode.LIST_CATEGORY)
    @ManyToOne
    @JoinColumn(name = "LIST_CATEGORY_ID")//, nullable = false)
    private ListCategory listCategory;

    @Getter @Setter
    @AuditedField(AuditedFieldCode.LIST_COUNTRY)
    @ManyToOne
    @JoinColumn(name = "COUNTRY_ID")//, nullable = false)
    private Country country;

    @Getter @Setter
    @AuditedField(AuditedFieldCode.LIST_OWNER)
    @Column(name = "OWNER", length = 1000)
    private String owner;

    @Getter @Setter
    @AuditedField(AuditedFieldCode.LIST_ISSUER)
    @ManyToOne
    @JoinColumn(name = "ISSUER_ID")//, nullable = false)
    private Issuer issuer;

    @Getter @Setter
    @AuditedField(AuditedFieldCode.LIST_REGULATOR_IP)
    @ManyToOne
    @JoinColumn(name = "REGULATOR_IP_ID")//, nullable = false)
    private RegulatorOrInternalPolicy regulatorOrInternalPolicy;

    @Getter @Setter
    @Column(name = "IS_DELETED")//, nullable = false)
    private boolean deleted;

    @Getter @Setter
    @AuditedField(AuditedFieldCode.LIST_IS_GLOBAL_PLUS_OFAC)
    @Column(name = "IS_GLOBAL_PLUS_OFAC")
    private boolean globalPlusOfac;

    @Getter @Setter
    @AuditedField(AuditedFieldCode.GENERIC_FORMAT)
    @Column(name = "GENERIC_FORMAT")
    private boolean genericFormat;

    @Getter @Setter
    @AuditedField(AuditedFieldCode.REGLISS_FORMAT)
    @Column(name = "REGLISS_FORMAT")
    private boolean reglissFormat;

    @Getter @Setter
    @AuditedField(AuditedFieldCode.VIGILANCE_FORMAT)
    @Column(name = "VIGILANCE_FORMAT")
    private boolean vigilanceFormat;

    @Getter @Setter
    @AuditedField(AuditedFieldCode.SAFEWATCH_FORMAT)
    @Column(name = "SAFEWATCH_FORMAT")
    private boolean safewatchFormat;

    @Getter @Setter
    @AuditedField(AuditedFieldCode.FIRCOSOFT_FORMAT)
    @Column(name = "FIRCOSOFT_FORMAT")
    private boolean fircosoftFormat;

    @Getter @Setter
    @AuditedField(AuditedFieldCode.DJ_CORE_XML_FORMAT)
    @Column(name = "DJ_CORE_XML_FORMAT")
    private boolean djCoreXmlFormat;

    @Getter @Setter
    @Embedded
    private ImportConfiguration importConfiguration = new ImportConfiguration();

    @Getter @Setter
    @AuditedField(AuditedFieldCode.LIST_GEOGRAPHIC_DATA)
    @ManyToOne
    @JoinColumn(name = "GEOGRAPHIC_DATA_ID")//, nullable = false)
    private GeographicData geographicData;

    @Setter
    @Getter
    @Column(name = "LAST_FAILED_DJ_FILENAME")
    private String lastFailedDjFilename;

    @OneToMany(mappedBy = "list", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<Version> versions = new HashSet<>();

    @AuditedCollection(createAction = AuditedAction.LIST_FILTER_ADDITION,
            updateAction = AuditedAction.LIST_FILTER_MODIFICATION,
            deleteAction = AuditedAction.LIST_FILTER_DELETION)
    @OneToMany(mappedBy = "list", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<DJExportFilter> filters = new HashSet<>();

    @AuditedCollection(createAction = AuditedAction.LIST_EMAIL_ADDITION,
            updateAction = AuditedAction.LIST_EMAIL_MODIFICATION,
            deleteAction = AuditedAction.LIST_EMAIL_DELETION)
    @OneToMany(mappedBy = "list", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("email")
    private SortedSet<BroadcastEmail> broadcastEmails = new TreeSet<>();

    @OneToMany(mappedBy = "list", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<MappingFile> mappingFiles = new HashSet<>();

    @Getter
    @OneToMany(mappedBy = "list", cascade = CascadeType.ALL)
    private Set<Subscription> subscriptions = new HashSet<>();

    @ElementCollection
    @CollectionTable(name = "REGLISSLIST_PREVIOUSERRORS", joinColumns = @JoinColumn(name = "REGLISSLIST_ID"))
    @Column(name = "PREVIOUS_ERRORS")
    private Set<String> previousErrors = new HashSet<>();

    @ManyToMany(mappedBy = "lists")
    private Set<ConcatenatedList> concatenatedLists = new HashSet<>();

    @AuditedCollection(createAction = AuditedAction.LIST_FILTER_ADDITION,
            updateAction = AuditedAction.LIST_FILTER_MODIFICATION,
            deleteAction = AuditedAction.LIST_FILTER_DELETION)
    @OneToMany(mappedBy = "list", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<SixFilters> sixFilters = new HashSet<>();

    @OneToMany(mappedBy = "list", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<StructuredFile> structuredFiles = new HashSet<>();

    @OneToMany(mappedBy = "list", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<InstrumentFile> instrumentFiles = new HashSet<>();

    @OneToMany(mappedBy = "list", cascade = CascadeType.ALL, orphanRemoval = true)
    private Set<OptionsFile> optionsFile = new HashSet<>();

    public ReglissList() {
    }


    public ReglissList(ReglissList other) {
        this.id = other.id;
        this.commentEn = other.commentEn;
        this.commentFr = other.commentFr;
        this.description = other.description;
        this.active = other.active;
        this.deleted = other.deleted;
        this.globalPlusOfac = other.globalPlusOfac;
        this.genericFormat = other.genericFormat;
        this.fircosoftFormat = other.fircosoftFormat;
        this.reglissFormat = other.reglissFormat;
        this.safewatchFormat = other.safewatchFormat;
        this.vigilanceFormat = other.vigilanceFormat;
        this.djCoreXmlFormat = false;
        this.name = other.name;
        this.nameNormalized = other.nameNormalized;
        this.reference = other.reference;
        this.shortName = other.shortName;
        this.shortNameNormalized = other.shortNameNormalized;
        this.type = other.type;
        this.regulatorOrInternalPolicy = other.regulatorOrInternalPolicy;
        this.confidentiality = other.confidentiality;
        this.country = other.country;
        this.listCategory = other.listCategory;
        this.issuer = other.issuer;
        this.owner = other.owner;
        this.scope = other.scope;
        this.geographicData = other.geographicData;
        if (other.importConfiguration != null) {
            this.importConfiguration = new ImportConfiguration(other.importConfiguration);
        }

        for (BroadcastEmail otherEmail : other.broadcastEmails) {
            addBroadcastEmail(new BroadcastEmail(otherEmail));
        }
        for (MappingFile otherFile : other.mappingFiles) {
            addMappingFile(new MappingFile(otherFile));
        }
        for (DJExportFilter otherFilter : other.filters) {
            addFilter(new DJExportFilter(otherFilter));
        }
        for (SixFilters otherFilter : other.sixFilters) {
            addSixFilter(new SixFilters(otherFilter));
        }
    }

    // =====================================================================================
    // TODO: LINES 278-356 WERE NOT IN THE SCREENSHOTS - copy them from the IDE and paste here.
    //       (Probably addBroadcastEmail, addMappingFile, getVersions, getBroadcastEmails, etc.)
    // =====================================================================================

    public final void addFilter(DJExportFilter filter) {
        filter.setList(this);
        this.filters.add(filter);
    }

    public final void addSixFilter(SixFilters filter) {
        filter.setList(this);
        this.sixFilters.add(filter);
    }

    public Set<SixFilters> getSixFilters() { return Collections.unmodifiableSet(this.sixFilters); }

    public Set<SixFilters> getActiveSixFilters() {
        return Collections.unmodifiableSet(this.sixFilters.stream()
                .filter(SixFilters::isActive)
                .collect(toSet()));
    }

    public Set<SixFilters> getActiveAndNotDeletedSixFilters() {
        return Collections.unmodifiableSet(this.sixFilters.stream()
                .filter(f->f.isActive()==true && f.isDeleted()==false)
                .collect(toSet()));
    }

    public Set<DJExportFilter> getActiveFilters() {
        return Collections.unmodifiableSet(this.filters.stream()
                .filter(DJExportFilter::isActive)
                .filter(f -> this.getImportFileType() == f.getFormat())
                .collect(toSet()));
    }

    public Set<DJExportFilter> getActiveAndNotDeletedFilters() {
        return Collections.unmodifiableSet(this.filters.stream()
                .filter(f->f.isActive()==true && f.isDeleted()==false)
                .filter(f -> this.getImportFileType() == f.getFormat())
                .collect(toSet()));
    }

    public Set<DJExportFilter> getActiveFiltersWithGenerteExportFiles() {
        return Collections.unmodifiableSet(this.filters.stream()
                .filter(DJExportFilter::isActive)
                .filter(DJExportFilter::isGenerateExportFiles)
                .filter(f -> this.getImportFileType() == f.getFormat())
                .collect(toSet()));
    }

    public Set<DJExportFilter> getFilters() { return Collections.unmodifiableSet(this.filters); }

    public Optional<MappingFile> getActiveMappingFile() {
        return mappingFiles.stream()
                .filter(MappingFile::isActive)
                .findAny();
    }

    public boolean allowsManualProcessing() {
        return importConfiguration.isAllowManualUpload() && getActiveMappingFile().isPresent();
    }

    public ImportFileType getImportFileType() { return importConfiguration.getImportFileType(); }

    public void setImportFileType(ImportFileType importFileType) { importConfiguration.setImportFileType(importFileType); }

    public List<FileExportFormat> getEnabledFileFormats() {
        if (allowsAutoUpload()) {
            if (getImportFileType().equals(ImportFileType.DOW_JONES_CORE)
                    || getImportFileType().equals(ImportFileType.CUSTOM) || getImportFileType().equals(ImportFileType.SIX_MAIN_FILE) || getImportFileType().equals(ImportFileType.DOW_JONES_WATCHLIST_EXTRA) ) {
                return getRegularFormats();
            } else if (filters.stream().filter(f -> f.getFormat() == ImportFileType.DOW_JONES_WATCHLIST)
                    .anyMatch(DJExportFilter::isActive)) {
                return singletonList(FileExportFormat.DJ_WATCHLIST);
            } else if (filters.stream().filter(f -> f.getFormat() == ImportFileType.DOW_JONES_AME)
                    .anyMatch(DJExportFilter::isActive)) {
                return singletonList(FileExportFormat.DJ_AME);
            }
            return emptyList();
        } else {
            return getRegularFormats();
        }
    }

    // =====================================================================================
    // TODO: LINES 445-489 WERE NOT IN THE SCREENSHOTS - copy them from the IDE and paste here.
    //       (Probably getRegularFormats, allowsAutoUpload, getLastApplicableVersion, etc.)
    // =====================================================================================

    /** @deprecated Query using {@link ListInProgressVersion} instead */
    @Deprecated
    public Optional<Version> getCurrentVersion() {
        return versions.stream()
                .filter(Version::isNotDeleted)
                .filter(Version::wasNotCertified)
                .max(comparing(Version::getVersionNumber));
    }

    public Optional<Version> getLastVersion() {
        return versions.stream()
                .filter(Version::isNotDeleted)
                .max(comparing(Version::getVersionNumber));
    }

    public boolean hasVersions() { return versions.stream().filter(Version::isNotDeleted).count() > 0; }

    public Optional<LocalDateTime> getLastPublicationDate() {
        return getLastApplicableVersion().map(Version::getPublicationDate);
    }

    public boolean canBeDeactivated() { return getLastVersion().equals(getLastApplicableVersion()); }

    public List<DJFileImportMode> getDJFileImportModes() {
        return importConfiguration.getImportFileType().djFileImportModes();
    }

    public void setDjCoreXmlFormat(boolean djCoreXmlFormat) { this.djCoreXmlFormat = false; }

    public Set<ConcatenatedList> getConcatenatedLists() { return unmodifiableSet(concatenatedLists); }
    public Set<StructuredFile> getStructuredFile() { return unmodifiableSet(this.structuredFiles); }
    public Set<InstrumentFile> getInstrumentFile() { return unmodifiableSet(this.instrumentFiles); }
    public Set<OptionsFile> getoptionsFile() { return unmodifiableSet(this.optionsFile); }
}
