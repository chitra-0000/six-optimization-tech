package com.bnpp.regliss.importer.six.extractor;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.dao.IncorrectResultSizeDataAccessException;

import javax.persistence.EntityManager;
import javax.persistence.TypedQuery;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// TODO: re-add project imports for: ReglissList, Version

@RunWith(MockitoJUnitRunner.Silent.class)
public class SixRecordStartDatesTest {

    @InjectMocks
    private SixRecordStartDates startDates;

    @Mock
    private EntityManager entityManager;

    @SuppressWarnings("unchecked")
    private TypedQuery<Object[]> query(List<Object[]> rows) {
        TypedQuery<Object[]> query = mock(TypedQuery.class);
        when(entityManager.createQuery(SixRecordStartDates.QUERY, Object[].class)).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.setHint(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(rows);
        return query;
    }

    @Test
    public void lastVersion_loadedWithOneQuery() {
        TypedQuery<Object[]> query = query(Arrays.asList(new Object[]{"R1", "2026-01-01"}, new Object[]{"R2", null},
                new Object[]{null, "2026-02-02"}));
        Version version = mock(Version.class);
        when(version.getId()).thenReturn(9L);
        ReglissList list = mock(ReglissList.class);
        when(list.getLastVersion()).thenReturn(Optional.of(version));

        SixRecordStartDates.Lookup lookup = startDates.ofLastVersion(list);

        verify(query).setParameter("versionId", 9L);
        verify(query).setHint("org.hibernate.fetchSize", 1000);
        verify(query).setHint("org.hibernate.readOnly", true);
        assertEquals(2, lookup.size());                                  // the NULL reference is skipped
        assertEquals(Optional.of("2026-01-01"), lookup.startDateOf("R1"));
        assertFalse(lookup.startDateOf("R2").isPresent());               // found without a start date
        assertFalse(lookup.startDateOf("r1").isPresent());               // case-sensitive
        assertFalse(lookup.startDateOf("UNKNOWN").isPresent());
        assertFalse(lookup.startDateOf(null).isPresent());
    }

    @Test
    public void noVersion_emptyLookup_noQuery() {
        ReglissList list = mock(ReglissList.class);
        when(list.getLastVersion()).thenReturn(Optional.empty());
        SixRecordStartDates.Lookup lookup = startDates.ofLastVersion(list);
        assertSame(SixRecordStartDates.Lookup.EMPTY, lookup);
        assertEquals(0, lookup.size());
        verify(entityManager, never()).createQuery(anyString(), any(Class.class));
    }

    @Test
    public void duplicatedReference_failsWhenLookedUp() {
        SixRecordStartDates.Lookup lookup = SixRecordStartDates.Lookup.of(Arrays.asList(
                new Object[]{"D", "a"}, new Object[]{"D", "b"}, new Object[]{"D", "c"}, new Object[]{"OK", "d"}));
        assertEquals(Optional.of("d"), lookup.startDateOf("OK"));
        try {
            lookup.startDateOf("D");
            fail();
        } catch (IncorrectResultSizeDataAccessException e) {
            assertEquals(1, e.getExpectedSize());
            assertEquals(3, e.getActualSize());
        }
    }

    @Test
    public void emptyRows() {
        assertEquals(0, SixRecordStartDates.Lookup.of(Collections.emptyList()).size());
    }
}
