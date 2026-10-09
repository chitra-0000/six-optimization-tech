package com.bnpp.regliss.entity.record;

// TODO: imports (lines 3-29) are folded in the photos - restore them with Alt+Enter / Optimize Imports in IntelliJ.
// Expected: javax.persistence.* (jakarta.* on Spring Boot 3), javax.validation.Valid / NotNull / NotEmpty,
// lombok.Getter / Setter, org.apache.commons.lang3.StringUtils, java.time.LocalDate, java.util.*,
// java.util.function.Predicate, static java.util.Arrays.asList, static java.util.Collections.emptyList,
// plus the Regliss classes (AuditedField, AuditedCollection, ImportableChild, ChildType, Version, ...).

@Entity
@Table(name = "RECORD",
        indexes = @Index(columnList = "RECORD_TYPE"))
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "RECORD_TYPE", discriminatorType = DiscriminatorType.STRING)
/**@SequenceGenerator(name = "RecordIdGenerator", initialValue = 100, sequenceName = "RECORD_SEQ", allocationSize = 100)*/
@GenericGenerator( name = "RecordIdGenerator", strategy = "org.hibernate.id.enhanced.SequenceStyleGenerator",
        parameters = {
                @org.hibernate.annotations.Parameter(name = "sequence_name", value = "RECORD_SEQ") } )
@SqlResultSetMapping(name="count",columns = { @ColumnResult(name= "count")})
@NamedNativeQueries(
        {@NamedNativeQuery(name = "markRecordsForPurge", query = "UPDATE RECORD set TO_VERSION=-1, list_id=-1, from_version=" + Long.MAX_VALUE + " WHERE LIST_ID=?2 AND FROM_VERSION=?1", resultSetMapping = "count"),
        @NamedNativeQuery(name = "revertRecordsToVersion", query = "UPDATE RECORD set DELETED_IN_VERSION = null, TO_VERSION = DEFAULT WHERE list_id=?2 AND TO_VERSION=?1", resultSetMapping = "count")
})
public abstract class Record extends AbstractSimpleEntity implements AuditableRecord, Validable {

    @Id
    @Getter
    @Setter
    @GeneratedValue( generator = "RecordIdGenerator")
    protected Long id;

    @NotNull(message="{record.recordType.mandatory}", groups = {ErrorConstraint.class, SemiAutoErrorConstraint.class})
    @Getter
    @Column(name = "RECORD_TYPE", insertable = false, updatable = false, nullable = false)
    @Enumerated(EnumType.STRING)
    protected final RecordType type;

    @ImportableReferentialCollection(code = AuditedFieldCode.RECORD_CATEGORY)
    @AuditedCollection(createAction = AuditedAction.RECORD_CATEGORY_ADDITION,
            deleteAction = AuditedAction.RECORD_CATEGORY_DELETION)
    @ManyToMany(cascade = CascadeType.MERGE)
    @JoinTable(name = "RECORD_CATEGORIES", joinColumns = @JoinColumn(name = "RECORD_ID"), inverseJoinColumns = @JoinColumn(name = "CATEGORY_ID"))
    private Set<RecordCategory> categories = new HashSet<>();

    @ImportableReferentialCollection(code = AuditedFieldCode.RECORD_SANCTION_TYPE)
    @AuditedCollection(createAction = AuditedAction.RECORD_SANCTION_TYPE_ADDITION,
            deleteAction = AuditedAction.RECORD_SANCTION_TYPE_DELETION)
    @ManyToMany(cascade = CascadeType.MERGE)
    @JoinTable(name = "RECORD_SANCTION_TYPE", joinColumns = @JoinColumn(name = "RECORD_ID"), inverseJoinColumns = @JoinColumn(name = "SANCTION_TYPE_ID"))
    private Set<SanctionType> sanctionTypes = new HashSet<>();

    @ImportableReferentialCollection(code = AuditedFieldCode.RECORD_DESCRIPTION3)
    @ManyToMany(cascade = CascadeType.MERGE)
    @JoinTable(name = "RECORD_DOW_JONES_DESCRIPTION", joinColumns = @JoinColumn(name = "RECORD_ID"), inverseJoinColumns = @JoinColumn(name = "DESCRIPTION3_ID"))
    @AuditedCollection(createAction = AuditedAction.RECORD_DESCRIPTION3_ADDITION,
            deleteAction = AuditedAction.RECORD_DESCRIPTION3_DELETION)
    private Set<Description3> descriptions3 = new HashSet<>();

    @Getter
    @Setter
    @ImportableReferentialCollection(code = AuditedFieldCode.RECORD_OCCUPATION_CATEGORY)
    @ManyToMany(cascade = CascadeType.MERGE)
    @JoinTable(name = "RECORD_PEP_OCCUPATION", joinColumns = @JoinColumn(name = "RECORD_ID"), inverseJoinColumns = @JoinColumn(name = "PEP_OCCUPATION_CATEGORY_ID"))
    @AuditedCollection(createAction = AuditedAction.RECORD_OCCUPATION_CATEGORY_ADDITION,
            deleteAction = AuditedAction.RECORD_OCCUPATION_CATEGORY_DELETION)
    private Set<PEPOccupationCategory> pepOccupationCategories = new HashSet<>();

    @CaseInsensitive
    @NotEmpty(message="{record.name.mandatory}", groups = {WarningConstraint.class, SemiAutoErrorConstraint.class})
    @Getter
    @EqualityField
    @AuditedField(AuditedFieldCode.RECORD_NAME)
    @Column(name = "NAME", length = 300)
    private String name;

    @Column(name = "NAME_NORMALIZED", length = 450)
    private String nameNormalized;

    @Getter
    @Setter
    @EqualityField
    @AuditedField(AuditedFieldCode.RECORD_DESCRIPTION)
    @Column(name = "DESCRIPTION", length = 300)
    private String description;

    @CaseInsensitive
    @Getter
    @Setter
    @AuditedField(AuditedFieldCode.RECORD_DOW_JONES_NOTES)
    @Column(name = "DOW_JONES_NOTES", length = 40000)
    @Lob
    private String dowJonesNotes;

    @Getter
    @Setter
    @EqualityField
    @AuditedField(AuditedFieldCode.RECORD_KEYWORDS)
    @Column(name = "KEYWORDS", length = 256)
    private String keywords;

    @CaseInsensitive
    @Getter
    @Setter
    @EqualityField
    @AuditedField(AuditedFieldCode.RECORD_COMMENTS)
    @Column(name = "COMMENTS", length = 2048)
    private String comments;

    @Getter
    @Setter
    @EqualityField
    @AuditedField(AuditedFieldCode.RECORD_ADDITIONAL_INFO)
    @Column(name = "ADDITIONAL_INFO", length = 2048)
    private String additionalInfo;

    @CaseInsensitive
    @Getter
    @Setter
    @EqualityField
    @AuditedField(AuditedFieldCode.RECORD_REGLISS_REFERENCE)
    @Column(name = "REGLISS_REFERENCE", length = 256)
    private String reglissReference;

    @Getter
    @Setter
    @Column(name = "RECORD_ID", length = 13)
    @AuditedField(AuditedFieldCode.RECORD_ID)
    private String recordId;

    @Getter @Setter
    @EqualityField
    @AuditedField(AuditedFieldCode.RECORD_START_DATE)
    @Column(name = "START_DATE", length = 255)
    private String startDateStr;

    @CaseInsensitive
    @NotEmpty(message="{record.externalReference.mandatory}", groups = {ErrorConstraint.class, SemiAutoErrorConstraint.class})
    @Getter
    @Setter
    @EqualityField
    @AuditedField(AuditedFieldCode.RECORD_EXTERNAL_REFERENCE)
    @Column(name = "EXTERNAL_REFERENCE", length = 250)
    private String externalReference;

    @Getter
    @Setter
    @EqualityField
    @AuditedField(AuditedFieldCode.RECORD_INSTRUCTION)
    @Column(name = "INSTRUCTION", length = 2048)
    private String instruction;

    @Valid
    @ImportableChild(type = ChildType.PROGRAM)
    @AuditedCollection(createAction = AuditedAction.RECORD_PROGRAM_ADDITION,
            updateAction = AuditedAction.RECORD_PROGRAM_MODIFICATION,
            deleteAction = AuditedAction.RECORD_PROGRAM_DELETION)
    @ManyToMany(cascade = CascadeType.ALL)
    @JoinTable(name = "RECORD_PROGRAM", joinColumns = @JoinColumn(name = "RECORD_ID"), inverseJoinColumns = @JoinColumn(name = "PROGRAM_ID"))
    private final Set<Program> programs = new HashSet<>();

    @Valid
    @ImportableChild(type = ChildType.ALIAS)
    @AuditedCollection(createAction = AuditedAction.RECORD_ALIAS_ADDITION,
            updateAction = AuditedAction.RECORD_ALIAS_MODIFICATION,
            deleteAction = AuditedAction.RECORD_ALIAS_DELETION)
    @ManyToMany(cascade = CascadeType.ALL)
    @JoinTable(name = "RECORD_ALIAS", joinColumns = @JoinColumn(name = "RECORD_ID"), inverseJoinColumns = @JoinColumn(name = "ALIAS_ID"))
    private final Set<Alias> aliases = new HashSet<>();

    @ImportableChild(type = ChildType.SANCTION_REFERENCE)
    @AuditedCollection(createAction = AuditedAction.RECORD_SANCTION_REFERENCE_ADDITION,
            updateAction = AuditedAction.RECORD_SANCTION_REFERENCE_MODIFICATION,
            deleteAction = AuditedAction.RECORD_SANCTION_REFERENCE_DELETION)
    @ManyToMany(cascade = CascadeType.ALL)
    @JoinTable(name = "RECORD_SANCTION_REFERENCE", joinColumns = @JoinColumn(name = "RECORD_ID"), inverseJoinColumns = @JoinColumn(name = "SANCTION_REFERENCE_ID"))
    private final Set<SanctionReference> sanctionReferences = new HashSet<>();

    @ImportableChild(type = ChildType.SOURCE)
    @AuditedCollection(createAction = AuditedAction.RECORD_SOURCE_ADDITION,
            updateAction = AuditedAction.RECORD_SOURCE_MODIFICATION,
            deleteAction = AuditedAction.RECORD_SOURCE_DELETION)
    @ManyToMany(cascade = CascadeType.ALL)
    @JoinTable(name = "RECORD_SOURCE", joinColumns = @JoinColumn(name = "RECORD_ID"), inverseJoinColumns = @JoinColumn(name = "SOURCE_ID"))
    private final Set<RecordSource> sources = new HashSet<>();

    @ImportableChild(type = ChildType.RELATION)
    @AuditedCollection(createAction = AuditedAction.RECORD_RELATION_ADDITION,
            updateAction = AuditedAction.RECORD_RELATION_MODIFICATION,
            deleteAction = AuditedAction.RECORD_RELATION_DELETION)
    @ManyToMany(cascade = CascadeType.ALL)
    @JoinTable(name = "RECORD_RELATION", joinColumns = @JoinColumn(name = "RECORD_ID"), inverseJoinColumns = @JoinColumn(name = "RECORD_RELATION_ID"))
    private final Set<RecordRelation> relations = new HashSet<>();

    @ImportableChild(type = ChildType.ASSOCIATION)
    @AuditedCollection(createAction = AuditedAction.RECORD_ASSOCIATION_ADDITION,
            updateAction = AuditedAction.RECORD_ASSOCIATION_MODIFICATION,
            deleteAction = AuditedAction.RECORD_ASSOCIATION_DELETION)
    @ManyToMany(cascade = CascadeType.ALL)
    @JoinTable(name = "RECORD_ASSOCIATION", joinColumns = @JoinColumn(name = "RECORD_ID"), inverseJoinColumns = @JoinColumn(name = "RECORD_ASSOCIATION_ID"))
    private final Set<RecordAssociation> associations = new HashSet<>();

    @Valid
    @ImportableChild(type = ChildType.ADDRESS)
    @AuditedCollection(createAction = AuditedAction.RECORD_ADDRESS_ADDITION,
            updateAction = AuditedAction.RECORD_ADDRESS_MODIFICATION,
            deleteAction = AuditedAction.RECORD_ADDRESS_DELETION)
    @ManyToMany(cascade = CascadeType.ALL)
    @JoinTable(name = "RECORD_ADDRESS", joinColumns = @JoinColumn(name = "RECORD_ID"), inverseJoinColumns = @JoinColumn(name = "ADDRESS_ID"))
    protected Set<Address> addresses = new HashSet<>();

    @Valid
    @ImportableChild(type = ChildType.IBAN)
    @ManyToMany(cascade = CascadeType.ALL)
    @AuditedCollection(createAction = AuditedAction.RECORD_IBAN_ADDITION,
            updateAction = AuditedAction.RECORD_IBAN_MODIFICATION,
            deleteAction = AuditedAction.RECORD_IBAN_DELETION)
    @JoinTable(name = "RECORD_IBAN", joinColumns = @JoinColumn(name = "RECORD_ID"), inverseJoinColumns = @JoinColumn(name = "IBAN_ID"))
    protected Set<CodeIban> ibanCodes = new HashSet<>();

    @Setter
    @Getter
    @OneToOne(cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @JoinColumn(name = "RECORD_DJ_ID")
    private RecordDJ recordDJ;

    @Getter
    @Setter
    @Column(name = "EXTERNAL_UUID")
    private String externalUuid = UUID.randomUUID().toString();

    @Getter
    @Setter
    @Column(name = "LAST_MODIFICATION_DATE")
    private String synthesisModDate;

    @CaseInsensitive
    @AuditedField(AuditedFieldCode.INACTIVITY_DATE_AS_PEP)
    @Column(name = "INACTIVITY_DATE_AS_PEP")
    private String inactivityDateAsPep;

    @CaseInsensitive
    @AuditedField(AuditedFieldCode.INACTIVITY_DATE_AS_RCA)
    @Column(name = "INACTIVITY_DATE_AS_RCA")
    private String inactivityDateAsRca;

    @Getter
    @Embedded
    private RecordVersionLinks versionLinks = new RecordVersionLinks();

    @ImportableChild(type = ChildType.DATE_DETAILS)
    @AuditedCollection(createAction = AuditedAction.RECORD_DATE_DETAILS_ADDITION,
            updateAction = AuditedAction.RECORD_DATE_DETAILS_MODIFICATION,
            deleteAction = AuditedAction.RECORD_DATE_DETAILS_DELETION)
    @ManyToMany(cascade = CascadeType.ALL)
    @JoinTable(name = "RECORD_DATE_DETAILS", joinColumns = @JoinColumn(name = "RECORD_ID"), inverseJoinColumns = @JoinColumn(name = "DATE_DETAILS_ID"))
    private final Set<DateDetails> datesDetails = new HashSet<>();

    @Valid
    @ImportableChild(type = ChildType.COUNTRY_DETAILS)
    @AuditedCollection(createAction = AuditedAction.RECORD_COUNTRY_DETAILS_ADDITION,
            updateAction = AuditedAction.RECORD_COUNTRY_DETAILS_MODIFICATION,
            deleteAction = AuditedAction.RECORD_COUNTRY_DETAILS_DELETION)
    @ManyToMany(cascade = CascadeType.ALL)
    @JoinTable(name = "RECORD_COUNTRY_DETAILS", joinColumns = @JoinColumn(name = "RECORD_ID"), inverseJoinColumns = @JoinColumn(name = "COUNTRY_DETAILS_ID"))
    private final Set<CountryDetails> countriesDetails = new HashSet<>();

    @Valid
    @ImportableChild(type = ChildType.IDENTITY)
    @AuditedCollection(createAction = AuditedAction.RECORD_IDENTITY_DOCUMENT_ADDITION,
            updateAction = AuditedAction.RECORD_IDENTITY_DOCUMENT_MODIFICATION,
            deleteAction = AuditedAction.RECORD_IDENTITY_DOCUMENT_DELETION)
    @ManyToMany(cascade = CascadeType.ALL)
    @JoinTable(name = "RECORD_IDENTITY_DOCUMENT", joinColumns = @JoinColumn(name = "RECORD_ID"), inverseJoinColumns = @JoinColumn(name = "IDENTITY_DOCUMENT_ID"))
    protected Set<IdentityDocument> identityDocuments = new HashSet<>();

    public Record(RecordType type) {
        this.type = type;
    }

    public Record(Record other) {
        this.id = other.id;
        this.reglissReference = other.reglissReference;
        this.externalReference = other.externalReference;
        this.recordId = other.recordId;
        this.versionLinks = new RecordVersionLinks(other.versionLinks);
        this.additionalInfo = other.additionalInfo;
        this.comments = other.comments;
        this.instruction = other.instruction;
        this.name = other.name;
        this.nameNormalized = other.nameNormalized;
        this.externalUuid = other.externalUuid;
        this.description = other.description;
        this.startDateStr = other.startDateStr;
        this.type = other.type;
        this.keywords = other.keywords;
        this.dowJonesNotes = other.dowJonesNotes;
        this.categories = new HashSet<>(other.categories);
        this.sanctionTypes = new HashSet<>(other.sanctionTypes);

        for (Alias otherAlias : other.aliases) {
            addAlias(new Alias(otherAlias));
        }
        for (Program otherProgram : other.programs) {
            addProgram(new Program(otherProgram));
        }

        for (SanctionReference otherSanctionReference : other.sanctionReferences) {
            addSanctionReference(new SanctionReference(otherSanctionReference));
        }

        for (RecordSource otherRecordSource : other.sources) {
            addSource(new RecordSource(otherRecordSource));
        }

        for (RecordRelation otherRecordRelation : other.relations) {
            addRelation(new RecordRelation(otherRecordRelation));
        }
        for (CodeIban otherCode : other.ibanCodes) {
            addIbanCode(new CodeIban(otherCode));
        }

        for (CountryDetails otherNationality : other.countriesDetails) {
            addCountryDetails(new CountryDetails(otherNationality));
        }

        for (DateDetails otherDateDetails : other.datesDetails) {
            addDateDetails(new DateDetails(otherDateDetails));
        }

    }

    public void copyUnmodifiableFieldsFrom(Record original) {
        this.reglissReference = original.reglissReference;
        this.externalReference = original.externalReference;
        this.recordId = original.recordId;
        this.versionLinks = new RecordVersionLinks(original.versionLinks);
        this.synthesisModDate = original.getSynthesisModDate();
        for (Alias myAlias : this.aliases) {
            original.aliases.stream()
                    .filter(a -> a.getId().equals(myAlias.getId()))
                    .findAny().ifPresent(myAlias::copyUnmodifiableFieldsFrom);
        }
    }

    public boolean wasCreatedInVersion(Long versionId) {
        return Objects.equals(versionId, versionLinks.getCreatedInVersionId());
    }

    public boolean isActiveSinceVersion(Long versionId) {
        return Objects.equals(versionId, versionLinks.getFromVersionId());
    }

    public boolean wasClonedInVersion(Long versionId) {
        return Objects.equals(versionId, versionLinks.getClonedInVersionId());
    }

    public String getFullName() {
        return name;
    }

    public Set<Address> getAddresses() {
        return Collections.unmodifiableSet(this.addresses);
    }

    public void addAddress(final Address address) {
        int indexOnRecord = addresses.stream().mapToInt(Address::getIndexOnRecord).max().orElse(0) + 1;
        address.setIndexOnRecord(indexOnRecord);
        addresses.add(address);
        address.addRecord(this);
    }

    public void removeAddress(final Address address) {
        addresses.remove(address);
        address.removeRecord(this);
    }

    public void setAddresses(Set<Address> newAddresses) {
        HashSet<Address> oldAddresses = new HashSet<>(addresses);
        oldAddresses.forEach(this::removeAddress);
        addresses.clear();
        newAddresses.forEach(this::addAddress);
    }

    public Set<CodeIban> getIbanCodes() {
        return Collections.unmodifiableSet(this.ibanCodes);
    }

    public void addIbanCode(final CodeIban code) {
        this.ibanCodes.add(code);
        code.addRecord(this);
    }

    public void removeIbanCode(final CodeIban code) {
        this.ibanCodes.remove(code);
        code.removeRecord(this);
    }

    public void setIbanCodes(Set<CodeIban> newCodes) {
        HashSet<CodeIban> oldCode = new HashSet<>(ibanCodes);
        oldCode.forEach(this::removeIbanCode);
        newCodes.forEach(this::addIbanCode);
    }

    public Set<Program> getPrograms() {
        return Collections.unmodifiableSet(this.programs);
    }

    public void addProgram(Program program) {
        programs.add(program);
        program.addRecord(this);
    }

    public void removeProgram(Program program) {
        this.programs.remove(program);
    }

    public void clearPrograms() {
        this.programs.clear();
    }

    public void setPrograms(Set<Program> newPrograms) {
        HashSet<Program> oldPrograms = new HashSet<>(programs);
        oldPrograms.forEach(this::removeProgram);
        newPrograms.forEach(this::addProgram);
    }

    public Set<Alias> getAliases() {
        return Collections.unmodifiableSet(this.aliases);
    }

    public void setAliases(Set<Alias> newAliases) {
        HashSet<Alias> oldAliases = new HashSet<>(aliases);
        oldAliases.forEach(this::removeAlias);
        aliases.clear();
        newAliases.forEach(this::addAlias);
    }

    public void addAlias(final Alias alias) {
        aliases.add(alias);
        alias.addRecord(this);
    }

    public void removeAlias(final Alias alias) {
        this.aliases.remove(alias);
    }

    public Set<SanctionReference> getSanctionReferences() {
        return Collections.unmodifiableSet(this.sanctionReferences);
    }

    public void setSanctionReferences(Set<SanctionReference> newSanctionReferences) {
        HashSet<SanctionReference> oldSanctionReferences = new HashSet<>(sanctionReferences);
        oldSanctionReferences.forEach(this::removeSanctionReference);
        sanctionReferences.clear();
        newSanctionReferences.forEach(this::addSanctionReference);
    }

    public void addSanctionReference(final SanctionReference sanctionReference) {
        sanctionReferences.add(sanctionReference);
        sanctionReference.addRecord(this);
    }

    public void removeSanctionReference(final SanctionReference sanctionReference) {
        this.sanctionReferences.remove(sanctionReference);
    }

    public void addSanctionType(final SanctionType sanctionType) {
        sanctionTypes.add(sanctionType);
    }

    public void addRecordCategory(final RecordCategory category) {
        categories.add(category);
    }

    public void removeRecordCategory(final RecordCategory category) {
        this.categories.remove(category);
    }

    public void addDescription3(final Description3 description3) {
        this.descriptions3.add(description3);
    }

    public void removeDescription3(final Description3 description3) {
        this.descriptions3.remove(description3);
    }

    public void removeSanctionType(final SanctionType sanctionType) {
        this.sanctionTypes.remove(sanctionType);
    }

    public Set<RecordSource> getSources() {
        return Collections.unmodifiableSet(this.sources);
    }

    public void setSources(Set<RecordSource> newSources) {
        HashSet<RecordSource> oldSources = new HashSet<>(sources);
        oldSources.forEach(this::removeSource);
        sources.clear();
        newSources.forEach(this::addSource);
    }

    public void addSource(final RecordSource source) {
        sources.add(source);
        source.addRecord(this);
    }

    public void removeSource(final RecordSource source) {
        this.sources.remove(source);
    }

    public Set<RecordRelation> getRelations() {
        return Collections.unmodifiableSet(this.relations);
    }

    public void setRelations(Set<RecordRelation> newRelations) {
        HashSet<RecordRelation> oldRelations = new HashSet<>(relations);
        oldRelations.forEach(this::removeRelation);
        relations.clear();
        newRelations.forEach(this::addRelation);
    }

    public void setAssociations(Set<RecordAssociation> newAssociations) {
        HashSet<RecordAssociation> oldAssociations = new HashSet<>(associations);
        oldAssociations.forEach(this::removeAssociation);
        associations.clear();
        newAssociations.forEach(this::addAssociation);
    }

    public Set<RecordCategory> getCategories() {
        return Collections.unmodifiableSet(this.categories);
    }

    public Set<Description3> getDescriptions3() {
        return Collections.unmodifiableSet(this.descriptions3);
    }

    public void setCategories(Set<RecordCategory> newCategories) {
        HashSet<RecordCategory> oldCategory = new HashSet<>(categories);
        oldCategory.forEach(this::removeRecordCategory);
        categories.clear();
        newCategories.forEach(this::addRecordCategory);
    }

    public void setDescriptions3(Set<Description3> newDescriptions3) {
        HashSet<Description3> oldDescriptions3 = new HashSet<>(descriptions3);
        oldDescriptions3.forEach(this::removeDescription3);
        descriptions3.clear();
        newDescriptions3.forEach(this::addDescription3);
    }

    public Set<SanctionType> getSanctionTypes() {
        return Collections.unmodifiableSet(this.sanctionTypes);
    }

    public void setSanctionTypes(Set<SanctionType> newSanctionTypes) {
        HashSet<SanctionType> oldSanctionTypes = new HashSet<>(sanctionTypes);
        oldSanctionTypes.forEach(this::removeSanctionType);
        sanctionTypes.clear();
        newSanctionTypes.forEach(this::addSanctionType);
    }

    public void addRelation(final RecordRelation relation) {
        relations.add(relation);
        relation.addRecord(this);
    }

    public void removeRelation(final RecordRelation relation) {
        this.relations.remove(relation);
    }

    public void addAssociation(final RecordAssociation association) {
        associations.add(association);
        association.addRecord(this);
    }

    public void removeAssociation(final RecordAssociation association) {
        this.associations.remove(association);
    }

    public Set<RecordAssociation> getAssociations() {
        return Collections.unmodifiableSet(this.associations);
    }

    public static Predicate<Record> byExternalRef(String extRef) {
        return record -> extRef.equals(record.getExternalReference());
    }

    public void assignNewReglissReferenceAndRecordId(long localReglissReference, Version version) {
        if(localReglissReference % 1000000 == 0) { //RecordID takes only the last 6 digits, so they cannot be all '0'
            localReglissReference ++;
        }
        reglissReference = version.getListVersionPrefix() + String.format("%06d", localReglissReference);
        recordId = computeRecordId(version, localReglissReference);
    }

    public void assignNewReglissReferenceAndRecordIdIfNoValue(long localReglissReference, Version version) {
        if (localReglissReference % 1000000 == 0) { //RecordID takes only the last 6 digits, so they cannot be all '0'
            localReglissReference++;
        }
        if (StringUtils.isEmpty(reglissReference)) {
            reglissReference = version.getListVersionPrefix() + String.format("%06d", localReglissReference);
        }
        if (StringUtils.isEmpty(recordId)) {
            recordId = computeRecordId(version, localReglissReference);
        }
    }

    private String computeRecordId(Version version, long localReglissReference) {
        int numberOfMillions = (int) localReglissReference / 1000000;
        if(numberOfMillions > 12) {
            throw new ReglissException(ErrorCode.RECORD_RECORD_ID_LIMIT_EXCEEDED);
        }
        String listIdentifier = String.format("%03d", Long.valueOf(version.getList().getReference()));
        char sequenceSeparator = (char) (numberOfMillions + (int) 'N');
        // e.g. 12 000 000 then numberOfMillionDigits = 2
        int numberOfMillionDigits = numberOfMillions != 0 ? String.valueOf(numberOfMillions).length() : 0;
        String elementNumber = String.format("%06d", localReglissReference).substring(numberOfMillionDigits);
        return listIdentifier + sequenceSeparator + elementNumber;
    }

    // WARNING! WARNING! WARNING! WARNING! WARNING! WARNING! WARNING! WARNING!
    // generation of Alias.reglissReference can happen independently of other Records in the same List
    // Address.reglissReferences on the other hand, are global per LIST
    public void fillAliasesReglissReference(String lastAliasReferenceSuffix) {
            for (Alias alias : aliases) {
            if (StringUtils.isEmpty(alias.getReglissReference())) {
                String nextAliasReference = Alias.generateNextAliasReference(lastAliasReferenceSuffix);
                alias.setReglissReference(new AliasReglissReference(reglissReference, nextAliasReference));
                lastAliasReferenceSuffix = nextAliasReference;
                if (StringUtils.isEmpty(alias.getExternalReference())) {
                    alias.setExternalReference(alias.getReglissReference());
                }
            }
        }
    }

    public boolean isNotCountryTypeRecord() {
        return type != RecordType.COUNTRY;
    }

    public void setStartDate(LocalDate date) {
        startDateStr = FormatDateUtil.getDateStringFromLocalDate(date);
    }

    public void setName(String name) {
        this.name = name;
        this.nameNormalized = TextNormalizerQueryUtil.normalize(name);
    }

    public List<String> getInactivityDateAsPep() {
        if (StringUtils.isBlank(inactivityDateAsPep)) {
            return emptyList();
        }
        return asList(inactivityDateAsPep.split(","));
    }

    public List<String> getInactivityDateAsRca() {
        if (StringUtils.isBlank(inactivityDateAsRca)) {
            return emptyList();
        }
        return asList(inactivityDateAsRca.split(","));
    }

    public void setInactivityDateAsPep(String inactivityDateAsPep) {
        this.inactivityDateAsPep = inactivityDateAsPep;
    }

    public void setInactivityDateAsRca(String inactivityDateAsRca) {
        this.inactivityDateAsRca = inactivityDateAsRca;
    }

    public Set<DateDetails> getDatesDetails() {
        return Collections.unmodifiableSet(this.datesDetails);
    }

    public void setDateDetails(Set<DateDetails> newDateDetails) {
        HashSet<DateDetails> oldDateDetails = new HashSet<>(datesDetails);
        oldDateDetails.forEach(this::removeDateDetails);
        datesDetails.clear();
        newDateDetails.forEach(this::addDateDetails);
    }

    public void addDateDetails(final DateDetails dateDetails) {
        datesDetails.add(dateDetails);
        dateDetails.addRecord(this);
    }

    public void removeDateDetails(final DateDetails dateDetails) {
        datesDetails.remove(dateDetails);
        dateDetails.removeRecord(this);
    }

    public void setCountriesDetails(Set<CountryDetails> newCountriesDetails) {
        HashSet<CountryDetails> oldCountriesDetails = new HashSet<>(countriesDetails);
        oldCountriesDetails.forEach(this::removeCountryDetails);
        countriesDetails.clear();
        newCountriesDetails.forEach(this::addCountryDetails);
    }

    public Set<CountryDetails> getCountriesDetails() {
        return Collections.unmodifiableSet(this.countriesDetails);
    }

    public void addCountryDetails(final CountryDetails countryDetails) {
        countriesDetails.add(countryDetails);
        countryDetails.addRecord(this);
    }

    public void removeCountryDetails(final CountryDetails countryDetails) {
        countriesDetails.remove(countryDetails);
        countryDetails.removeRecord(this);
    }

    public Set<IdentityDocument> getIdentityDocuments() {
        return Collections.unmodifiableSet(this.identityDocuments);
    }

    public void setIdentityDocuments(Set<IdentityDocument> newIdentityDocuments) {
        HashSet<IdentityDocument> oldIdentityDocuments = new HashSet<>(identityDocuments);
        oldIdentityDocuments.forEach(this::removeIdentityDocument);
        identityDocuments.clear();
        newIdentityDocuments.forEach(this::addIdentityDocument);
    }

    public void addIdentityDocument(final IdentityDocument identityDocument) {
        identityDocuments.add(identityDocument);
        identityDocument.addRecord(this);
    }

    public void removeIdentityDocument(final IdentityDocument identityDocument) {
        identityDocuments.remove(identityDocument);
        identityDocument.removeRecord(this);
    }

    public void createInVersion(Version version) {
        versionLinks.updateLinksAtCreateInVersion(version);
    }

    public void cloneInVersionFromRecord(Version version, Version previousVersion, Record oldRecord) {
        this.versionLinks.updateLinksAtCloneInVersion(version);
        oldRecord.versionLinks.endAtVersion(previousVersion);
    }

    public void deleteInVersion(Version version, Version previousVersion) {
        versionLinks.updateLinksAtDeleteInVersion(version, previousVersion);
    }

    public void markForPurge() {
        versionLinks.updateLinksForPurge();
    }

    @Override
    public String getBusinessId() {
        return externalReference;
    }

}
