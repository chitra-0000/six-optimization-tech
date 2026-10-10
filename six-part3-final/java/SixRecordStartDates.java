package com.bnpp.regliss.importer.six.extractor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.stereotype.Component;

import javax.persistence.EntityManager;            // jakarta.persistence.* on Spring Boot 3
import javax.persistence.PersistenceContext;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

// TODO: re-add project import for: ReglissList (entity)

/**
 * SIX XML step (point E): start date (Record.startDateStr) of every record of the last version of an output list,
 * read with ONE query per list instead of one RecordRepository.findByExternalReferenceAndVersionId per record
 * (about 49 000 queries per list, each loading a full Record entity: 15 to 25 minutes per list).
 *
 * Same result as the per-record query:
 *  - same JPQL join and version conditions as findByExternalReferenceAndVersionId, without the external reference;
 *  - record found with a start date -> that date; record not found, or found with no start date -> empty (the caller
 *    keeps its fallback, the application date of the file name);
 *  - the same external reference twice in the version -> IncorrectResultSizeDataAccessException when it is looked up,
 *    as the Optional single-result query threw before;
 *  - exact (case-sensitive) comparison of the external reference, like the SQL "=".
 * Only two columns are read (no Record entity, nothing added to the persistence context).
 */
@Slf4j
@Component
public class SixRecordStartDates {

    static final String QUERY = "SELECT r.externalReference, r.startDateStr FROM Record r JOIN Version v"
            + " ON r.versionLinks.listId = v.list.id AND v.id = :versionId"
            + " AND r.versionLinks.fromVersionId <= :versionId AND r.versionLinks.toVersionId >= :versionId";
    private static final int FETCH_SIZE = 1000;

    @PersistenceContext
    private EntityManager entityManager;

    /** Start dates of the records of the list's last version; empty lookup when the list has no version. */
    public Lookup ofLastVersion(ReglissList list) {
        return list.getLastVersion().map(v -> load(v.getId())).orElse(Lookup.EMPTY);
    }

    Lookup load(Long versionId) {
        long start = System.currentTimeMillis();
        List<Object[]> rows = entityManager.createQuery(QUERY, Object[].class)
                .setParameter("versionId", versionId)
                .setHint("org.hibernate.fetchSize", FETCH_SIZE)
                .setHint("org.hibernate.readOnly", true)
                .getResultList();
        Lookup lookup = Lookup.of(rows);
        log.info("SIX XML step: start dates of {} records of version {} read in {} ms", rows.size(), versionId,
                System.currentTimeMillis() - start);
        return lookup;
    }

    /** External reference -> start date of one version. */
    public static final class Lookup {
        static final Lookup EMPTY = new Lookup(Collections.emptyMap(), Collections.emptyMap());

        private final Map<String, String> startDates;
        private final Map<String, Integer> duplicates;

        private Lookup(Map<String, String> startDates, Map<String, Integer> duplicates) {
            this.startDates = startDates;
            this.duplicates = duplicates;
        }

        static Lookup of(List<Object[]> rows) {
            Map<String, String> dates = new HashMap<>(Math.max(16, rows.size() * 4 / 3 + 1));
            Set<String> seen = new HashSet<>();
            Map<String, Integer> duplicates = new HashMap<>();
            for (Object[] row : rows) {
                String reference = (String) row[0];
                if (reference == null) {
                    continue;   // "r.externalReference = ?1" never matches NULL
                }
                if (!seen.add(reference)) {
                    duplicates.merge(reference, 2, (count, two) -> count + 1);
                }
                dates.put(reference, (String) row[1]);
            }
            return new Lookup(dates, duplicates);
        }

        /**
         * Start date of the record with this external reference; empty when there is no such record or it has no
         * start date.
         *
         * @throws IncorrectResultSizeDataAccessException when the version has several records with this reference
         */
        public Optional<String> startDateOf(String externalReference) {
            if (externalReference == null) {
                return Optional.empty();
            }
            Integer count = duplicates.get(externalReference);
            if (count != null) {
                throw new IncorrectResultSizeDataAccessException("Several records with external reference "
                        + externalReference + " in the version", 1, count);
            }
            return Optional.ofNullable(startDates.get(externalReference));
        }

        public int size() {
            return startDates.size();
        }
    }
}
