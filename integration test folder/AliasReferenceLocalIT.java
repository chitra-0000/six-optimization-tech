package com.bnpp.regliss.entity.record;

// TODO: imports (lines 3-21) were folded in the IDE; reconstructed from usage.
//       Verify packages of project classes (InMemoryDb, AbstractTransactedRepositoryTestBase, repositories,
//       RecordService, ReglissList, ReglissListBuilder, Version, PersonRecord, Alias) and the assertThat source
//       (AssertJ assumed because of containsExactlyInAnyOrder / Assertions.assertThat).
import com.bnpp.regliss.InMemoryDb;
import org.assertj.core.api.Assertions;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import static java.util.Arrays.asList;
import static java.util.stream.Collectors.toList;
import static org.assertj.core.api.Assertions.assertThat;

@InMemoryDb
public class AliasReferenceLocalIT extends AbstractTransactedRepositoryTestBase {
    protected static final String LIST_REFERENCE = "222";

    @Autowired
    private RecordRepository recordRepo;
    @Autowired
    private AuditRepository auditRepo;
    @Autowired
    private VersionRepository versionRepo;
    @Autowired
    private ReglissListRepository listRepo;
    @Autowired
    private RecordService recordService;

    ReglissList reglissList;
    Version version1;
    Version version2;
    Version version3;

    @Before
    public void setup() {
        Assertions.assertThat(versionRepo.findAll()).isNotNull();
        createListAndVersions();
    }

    @Test
    public void test1() {
        PersonRecord recordInFirstVersion = aRecordWithAliases(asList("AAA", "BBB"));
        recordService.createRecord(recordInFirstVersion, version1);
        assertThat(getAliasReferences(recordInFirstVersion)).containsExactlyInAnyOrder("222.1.000001A", "222.1.000001B");

        PersonRecord recordInSecondVersion = anUpdatedRecordWithAliases(recordInFirstVersion, asList("BBB", "AAA"));
        recordService.update(recordInSecondVersion, version2);
        assertThat(getAliasReferences(recordInSecondVersion)).containsExactlyInAnyOrder("222.1.000001A", "222.1.000001B");
    }

    @Test
    public void test2() {
        PersonRecord recordInFirstVersion = aRecordWithAliases(asList("AAA", "BBB"));
        recordService.createRecord(recordInFirstVersion, version1);
        assertThat(getAliasReferences(recordInFirstVersion)).containsExactlyInAnyOrder("222.1.000001A", "222.1.000001B");

        Alias unchangedAlias = getAliasInRecordByReglissReference(recordInFirstVersion, "222.1.000001A");
        PersonRecord recordInSecondVersion = anUpdatedRecordWithAliases(recordInFirstVersion, asList(unchangedAlias.getLastName(), "CCC"));
        recordService.update(recordInSecondVersion, version2);
        assertThat(getAliasReferences(recordInSecondVersion)).containsExactlyInAnyOrder("222.1.000001A", "222.1.000001C");
    }

    @Test
    public void test3() {
        PersonRecord recordInFirstVersion = aRecordWithAliases(asList("AAA", "BBB"));
        recordService.createRecord(recordInFirstVersion, version1);
        assertThat(getAliasReferences(recordInFirstVersion)).containsExactlyInAnyOrder("222.1.000001A", "222.1.000001B");

        Alias unchangedAlias = getAliasInRecordByReglissReference(recordInFirstVersion, "222.1.000001A");
        PersonRecord recordInSecondVersion = anUpdatedRecordWithAliases(recordInFirstVersion, asList(unchangedAlias.getLastName(), "CCC"));
        recordService.update(recordInSecondVersion, version2);
        assertThat(getAliasReferences(recordInSecondVersion)).containsExactlyInAnyOrder("222.1.000001A", "222.1.000001C");

        PersonRecord recordInThirdVersion = anUpdatedRecordWithAliases(recordInSecondVersion, asList("CCC"));
        recordService.update(recordInThirdVersion, version3);
        assertThat(getAliasReferences(recordInThirdVersion)).containsExactlyInAnyOrder("222.1.000001C");
    }

    @Test
    public void test4() {
        PersonRecord recordInFirstVersion = aRecordWithAliases(asList("AAA", "BBB"));
        recordService.createRecord(recordInFirstVersion, version1);
        assertThat(getAliasReferences(recordInFirstVersion)).containsExactlyInAnyOrder("222.1.000001A", "222.1.000001B");

        Alias unchangedAlias = getAliasInRecordByReglissReference(recordInFirstVersion, "222.1.000001A");
        PersonRecord recordInSecondVersion = anUpdatedRecordWithAliases(recordInFirstVersion, asList(unchangedAlias.getLastName(), "CCC"));
        recordService.update(recordInSecondVersion, version2);
        assertThat(getAliasReferences(recordInSecondVersion)).containsExactlyInAnyOrder("222.1.000001A", "222.1.000001C");

        PersonRecord recordInThirdVersion = anUpdatedRecordWithAliases(recordInSecondVersion, asList("DDD"));
        recordService.update(recordInThirdVersion, version3);
        assertThat(getAliasReferences(recordInThirdVersion)).containsExactlyInAnyOrder("222.1.000001D");
    }

    private List<String> getAliasReferences(PersonRecord recordInFirstVersion) {
        return recordInFirstVersion.getAliases().stream().map(Alias::getReglissReference).collect(toList());
    }

    private Alias getAliasInRecordByReglissReference(PersonRecord recordInFirstVersion, String reference) {
        return recordInFirstVersion.getAliases().stream().filter(a -> a.getReglissReference().equals(reference))
                .findFirst().get();
    }

    private void createListAndVersions() {
        Optional<ReglissList> testList = listRepo.findByReferenceAndDeletedFalse(LIST_REFERENCE);
        reglissList = testList.orElseGet(() -> new ReglissListBuilder().withReference(LIST_REFERENCE).build());
        listRepo.save(reglissList);
        version1 = createVersionInList(1L);
        version2 = createVersionInList(2L);
        version3 = createVersionInList(3L);
    }

    private Version createVersionInList(long versionNuber) {
        Version version = new Version();
        version.setVersionNumber(versionNuber);
        reglissList.addVersion(version);
        em.flush();
        return version;
    }

    private PersonRecord aRecordWithAliases(List<String> aliasNames) {
        PersonRecord record = new PersonRecord();
        for (String aliasName : aliasNames) {
            Alias alias = anAliasWithLastName(aliasName);
            record.addAlias(alias);
        }
        return record;
    }

    private Alias anAliasWithLastName(String aliasName) {
        Alias alias = new Alias();
        alias.setLastName(aliasName);
        return alias;
    }

    private PersonRecord anUpdatedRecordWithAliases(PersonRecord recordToBeUpdated, List<String> aliasNames) {
        PersonRecord updatedRecord = new PersonRecord(recordToBeUpdated);
        updatedRecord.setAliases(new HashSet());
        for (String aliasName : aliasNames) {
            Alias alias = recordToBeUpdated.getAliases().stream().filter(a -> a.getLastName().equals(aliasName)).findFirst()
                    .orElse(anAliasWithLastName(aliasName));
            updatedRecord.addAlias(alias);
        }
        return updatedRecord;
    }

    @After
    public void cleanUp() {
        auditRepo.deleteAll();
        recordRepo.deleteAll();
        versionRepo.deleteAll();
        listRepo.deleteAll();
    }

}
