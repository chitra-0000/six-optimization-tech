package com.bnpp.regliss.importer.six.service;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.CallbackPreferringPlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import javax.persistence.Column;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToMany;
import javax.persistence.ManyToOne;
import javax.persistence.OneToMany;
import javax.persistence.OneToOne;
import javax.persistence.Table;
import javax.persistence.Transient;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Test entities are declared here (only their JPA annotations and public accessors are used by the writer). */
@RunWith(MockitoJUnitRunner.Silent.class)
public class SixJdbcBulkWriterTest {

    @InjectMocks
    private SixJdbcBulkWriter writer;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private Environment environment;

    // ------------------------------------------------------------------------------------------ test entities

    public enum Color { RED, BLUE }

    public static class Base {
        @Id
        private Long id;
        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
    }

    public static class Related {
        private final Long id;
        public Related(Long id) { this.id = id; }
        public Long getId() { return id; }
    }

    @Table(name = "TEST_PARENT")
    public static class Parent extends Base {
        public static final String CONSTANT = "not a column";
        private transient String cache;
        @Transient
        private String notSaved;
        @Column(name = "NAME")
        private String name;
        @Column(name = "LIST_ID")
        private Long listId;
        @Column(name = "VERSION_ID")
        private Long versionId;
        @Column(name = "\"QUOTED_COL\"")
        private Integer quoted;
        @Column
        private BigDecimal amount;
        @Column(name = "FLAG")
        private boolean flag;
        @Column(name = "BOXED_FLAG")
        private Boolean boxedFlag;
        @Column(name = "COLOR_ORD")
        private Color colorOrdinal;
        @Column(name = "COLOR_STR")
        @Enumerated(EnumType.STRING)
        private Color colorString;
        @Column(name = "CREATED")
        private Date created;
        @Column(name = "DAY")
        private java.sql.Date day;
        @Column(name = "LOCAL_DAY")
        private LocalDate localDay;
        @Column(name = "OTHER")
        private StringBuilder other;
        @Column(name = "READ_ONLY", insertable = false)
        private String readOnly;
        @ManyToOne
        @JoinColumn(name = "RELATED_ID")
        private Related related;
        @OneToOne
        @JoinColumn(name = "NOT_WRITTEN_ID", insertable = false)
        private Related notWritten;
        @OneToMany
        private List<Child> children = new ArrayList<>();

        public String getCache() { return cache; }
        public String getNotSaved() { return notSaved; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public Long getListId() { return listId; }
        public Long getVersionId() { return versionId; }
        public Integer getQuoted() { return quoted; }
        public BigDecimal getAmount() { return amount; }
        public boolean isFlag() { return flag; }
        public Boolean getBoxedFlag() { return boxedFlag; }
        public Color getColorOrdinal() { return colorOrdinal; }
        public Color getColorString() { return colorString; }
        public Date getCreated() { return created; }
        public java.sql.Date getDay() { return day; }
        public LocalDate getLocalDay() { return localDay; }
        public StringBuilder getOther() { return other; }
        public String getReadOnly() { return readOnly; }
        public Related getRelated() { return related; }
        public Related getNotWritten() { return notWritten; }
        public List<Child> getChildren() { return children; }
    }

    @Table(name = "TEST_CHILD")
    public static class Child extends Base {
        @Column(name = "SIX_INSTRU_ID")
        private Long parentId;
        @Column(name = "TEXT")
        private String text;
        public Long getParentId() { return parentId; }
        public String getText() { return text; }
    }

    public static class NoTable extends Base { }

    @Table(name = "")
    public static class EmptyTable extends Base { }

    @Table(name = "NO_ID")
    public static class NoId {
        @Column(name = "A")
        private String a;
        public String getA() { return a; }
    }

    @Table(name = "UNMAPPED")
    public static class Unmapped extends Base {
        private String plain;
        public String getPlain() { return plain; }
    }

    @Table(name = "RELATION_WITHOUT_JOIN")
    public static class RelationWithoutJoin extends Base {
        @ManyToOne
        private Related related;
        public Related getRelated() { return related; }
    }

    @Table(name = "NO_GETTER")
    public static class NoGetter extends Base {
        @Column(name = "A")
        private String a;
    }

    /** Non-static inner class: has the synthetic this$0 field; the @ManyToMany field is not a column. */
    @Table(name = "INNER_ENTITY")
    public class InnerEntity extends Base {
        @ManyToMany
        private List<Related> many;
        public List<Related> getMany() { return many; }
    }

    @Table(name = "IFACE")
    public interface IfaceEntity { }

    @Table(name = "BAD NAME")
    public static class BadName extends Base { }

    @Table(name = "FAILING")
    public static class Failing {
        @Id
        private Long id;
        @Column(name = "A")
        private String a;
        public Long getId() { return id; }
        public void setId(Long id) { throw new IllegalStateException("no"); }
        public String getA() { throw new IllegalStateException("no"); }
    }

    // ------------------------------------------------------------------------------------------ set-up

    private final List<BatchPreparedStatementSetter> batches = new ArrayList<>();
    private final Map<String, List<BatchPreparedStatementSetter>> batchesBySql = new HashMap<>();

    @Before
    public void setUp() {
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        writer.init();
        when(jdbcTemplate.queryForList(startsWith("SELECT OWNER"), anyString())).thenAnswer(inv -> {
            Map<String, Object> row = new HashMap<>();
            row.put("OWNER", "REGLISS");
            row.put("SEQUENCE_NAME", "ISEQ$$_" + inv.getArgument(1));
            return Collections.singletonList(row);
        });
        when(jdbcTemplate.queryForList(anyString(), eq(Long.class), anyInt())).thenAnswer(inv -> {
            int count = inv.getArgument(2);
            List<Long> ids = new ArrayList<>();
            for (long i = 1; i <= count; i++) {
                ids.add(i * 100);
            }
            return ids;
        });
        when(jdbcTemplate.batchUpdate(anyString(), any(BatchPreparedStatementSetter.class))).thenAnswer(inv -> {
            BatchPreparedStatementSetter setter = inv.getArgument(1);
            batches.add(setter);
            batchesBySql.computeIfAbsent(inv.getArgument(0), k -> new ArrayList<>()).add(setter);
            return new int[setter.getBatchSize()];
        });
    }

    private static Parent parent(String name, Child... kids) {
        Parent p = new Parent();
        p.name = name;
        p.children.addAll(Arrays.asList(kids));
        return p;
    }

    private static Child child(String text) {
        Child c = new Child();
        c.text = text;
        return c;
    }

    // ------------------------------------------------------------------------------------------ insertBatch

    @Test
    public void insertBatch_empty() {
        assertEquals(0, writer.insertBatch(Collections.<Parent>emptyList(), 1L, 2L, Parent::getChildren, "SIX_INSTRU_ID"));
        verify(transactionManager, never()).getTransaction(any());
    }

    @Test
    public void insertBatch_parentsAndChildren() throws SQLException {
        Parent p1 = parent("a", child("x"), null, child("y"));
        Parent p2 = parent("b");
        p2.children = null;
        int written = writer.insertBatch(Arrays.asList(p1, p2), 7L, 8L, Parent::getChildren, "SIX_INSTRU_ID");

        assertEquals(2, written);
        assertEquals(Long.valueOf(100L), p1.getId());
        assertEquals(Long.valueOf(200L), p2.getId());
        String parentSql = "INSERT INTO TEST_PARENT (ID, NAME, LIST_ID, VERSION_ID, QUOTED_COL, AMOUNT, FLAG, BOXED_FLAG, COLOR_ORD,"
                + " COLOR_STR, CREATED, DAY, LOCAL_DAY, OTHER, RELATED_ID) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        assertTrue(batchesBySql.keySet().toString(), batchesBySql.containsKey(parentSql));
        assertTrue(batchesBySql.containsKey("INSERT INTO TEST_CHILD (ID, SIX_INSTRU_ID, TEXT) VALUES (?, ?, ?)"));
        verify(jdbcTemplate).queryForList("SELECT \"REGLISS\".\"ISEQ$$_TEST_PARENT\".NEXTVAL FROM DUAL CONNECT BY LEVEL <= ?", Long.class, 2);

        // parent rows: LIST_ID / VERSION_ID forced, other columns from the entity
        BatchPreparedStatementSetter parents = batchesBySql.get(parentSql).get(0);
        assertEquals(2, parents.getBatchSize());
        PreparedStatement ps = mock(PreparedStatement.class);
        parents.setValues(ps, 0);
        verify(ps).setLong(1, 100L);
        verify(ps).setString(2, "a");
        verify(ps).setLong(3, 7L);
        verify(ps).setLong(4, 8L);

        // child rows: FK = id of their parent
        BatchPreparedStatementSetter kids = batchesBySql.get("INSERT INTO TEST_CHILD (ID, SIX_INSTRU_ID, TEXT) VALUES (?, ?, ?)").get(0);
        PreparedStatement cps = mock(PreparedStatement.class);
        kids.setValues(cps, 1);
        verify(cps).setLong(1, 200L);
        verify(cps).setLong(2, 100L);
        verify(cps).setString(3, "y");
    }

    @Test
    public void insertBatch_withoutChildren_andMoreThanOneJdbcChunk() {
        List<Child> rows = new ArrayList<>();
        for (int i = 0; i < 1001; i++) {
            rows.add(child("t" + i));
        }
        assertEquals(0, writer.insertBatch(rows, 1L, 2L, c -> Collections.emptyList(), "X"));
        assertEquals(2, batches.size());
        assertEquals(1000, batches.get(0).getBatchSize());
        assertEquals(1, batches.get(1).getBatchSize());
        // mapping and sequence read once for the class
        writer.insertBatch(Collections.singletonList(child("z")), 1L, 2L, c -> null, "X");
        verify(jdbcTemplate, times(1)).queryForList(startsWith("SELECT OWNER"), anyString());
    }

    @Test
    public void bind_everyValueType() throws SQLException {
        Parent p = parent("n");
        p.quoted = 3;
        p.amount = BigDecimal.TEN;
        p.flag = true;
        p.boxedFlag = Boolean.FALSE;
        p.colorOrdinal = Color.BLUE;
        p.colorString = Color.RED;
        p.created = new Date(1000L);
        p.day = new java.sql.Date(2000L);
        p.localDay = LocalDate.of(2026, 1, 2);
        p.other = new StringBuilder("sb");
        p.related = new Related(77L);
        writer.insertBatch(Collections.singletonList(p), 1L, 2L, x -> null, "X");
        PreparedStatement ps = mock(PreparedStatement.class);
        batches.get(0).setValues(ps, 0);
        verify(ps).setObject(5, 3);
        verify(ps).setObject(6, BigDecimal.TEN);
        verify(ps).setInt(7, 1);
        verify(ps).setInt(8, 0);
        verify(ps).setInt(9, 1);
        verify(ps).setString(10, "RED");
        verify(ps).setTimestamp(11, new Timestamp(1000L));
        verify(ps).setObject(12, p.day);
        verify(ps).setObject(13, p.localDay);
        verify(ps).setObject(14, p.other);
        verify(ps).setLong(15, 77L);
    }

    @Test
    public void bind_nullValues() throws SQLException {
        Parent p = parent(null);
        p.related = new Related(null);
        writer.insertBatch(Collections.singletonList(p), 1L, 2L, x -> null, "X");
        PreparedStatement ps = mock(PreparedStatement.class);
        batches.get(0).setValues(ps, 0);
        verify(ps).setNull(2, Types.VARCHAR);        // String
        verify(ps).setNull(5, Types.NUMERIC);        // Integer
        verify(ps).setNull(6, Types.NUMERIC);        // BigDecimal
        verify(ps).setNull(8, Types.NUMERIC);        // Boolean
        verify(ps).setNull(9, Types.VARCHAR);        // enum
        verify(ps).setNull(11, Types.TIMESTAMP);     // java.util.Date
        verify(ps).setNull(13, Types.TIMESTAMP);     // java.time
        verify(ps).setNull(14, Types.VARCHAR);       // other type
        verify(ps).setNull(15, Types.NUMERIC);       // related without id

        p.related = null;
        PreparedStatement ps2 = mock(PreparedStatement.class);
        batches.get(0).setValues(ps2, 0);
        verify(ps2).setNull(15, Types.NUMERIC);      // no related entity
    }

    @Test
    public void bind_getterError_isSqlException() {
        Failing f = new Failing();
        try {
            writer.insertBatch(Collections.singletonList(f), 1L, 2L, x -> null, "X");
            fail();
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("Cannot set the id of Failing"));
        }
    }

    @Test
    public void bind_readError() {
        writer.insertBatch(Collections.<Parent>singletonList(new FailingParent()), 1L, 2L, x -> null, "X");
        try {
            batches.get(0).setValues(mock(PreparedStatement.class), 0);
            fail();
        } catch (SQLException e) {
            assertTrue(e.getMessage().contains("Cannot read getName for column NAME"));
        }
    }

    @Table(name = "TEST_PARENT")
    public static class FailingParent extends Parent {
        @Override
        public String getName() {
            throw new IllegalStateException("read");
        }
    }

    // ------------------------------------------------------------------------------------------ sequences

    @Test
    public void sequenceOverride_used_blankIgnored() {
        when(environment.getProperty("six.import.id-sequence.TEST_CHILD")).thenReturn(" owner.my_seq ");
        when(environment.getProperty("six.import.id-sequence.TEST_PARENT")).thenReturn("  ");
        writer.insertBatch(Collections.singletonList(parent("a", child("c"))), 1L, 2L, Parent::getChildren, "SIX_INSTRU_ID");
        verify(jdbcTemplate).queryForList("SELECT \"OWNER\".\"MY_SEQ\".NEXTVAL FROM DUAL CONNECT BY LEVEL <= ?", Long.class, 1);
        verify(jdbcTemplate, times(1)).queryForList(startsWith("SELECT OWNER"), eq("TEST_PARENT"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void sequenceOverride_badName() {
        when(environment.getProperty("six.import.id-sequence.TEST_CHILD")).thenReturn("owner.bad name");
        writer.insertBatch(Collections.singletonList(child("c")), 1L, 2L, x -> null, "X");
    }

    @Test(expected = IllegalStateException.class)
    public void sequence_notFound() {
        when(jdbcTemplate.queryForList(startsWith("SELECT OWNER"), eq("TEST_CHILD"))).thenReturn(Collections.emptyList());
        writer.insertBatch(Collections.singletonList(child("c")), 1L, 2L, x -> null, "X");
    }

    @Test(expected = IllegalStateException.class)
    public void nextIds_wrongCount() {
        when(jdbcTemplate.queryForList(anyString(), eq(Long.class), anyInt())).thenReturn(Collections.singletonList(1L));
        writer.insertBatch(Arrays.asList(child("a"), child("b")), 1L, 2L, x -> null, "X");
    }

    // ------------------------------------------------------------------------------------------ deleteVersion

    @Test
    public void deleteVersion() {
        when(jdbcTemplate.update("DELETE FROM TEST_PARENT WHERE VERSION_ID = ?", 5L)).thenReturn(12);
        assertEquals(12, writer.deleteVersion(Parent.class, 5L));
    }

    @Test
    public void deleteVersion_syntheticAndManyToManyFieldsSkipped() {
        when(jdbcTemplate.update("DELETE FROM INNER_ENTITY WHERE VERSION_ID = ?", 6L)).thenReturn(3);
        assertEquals(3, writer.deleteVersion(InnerEntity.class, 6L));
    }

    @Test
    public void transactionWithoutResult_zero() {
        ReflectionTestUtils.setField(writer, "newTx", new TransactionTemplate(mock(CallbackPreferringPlatformTransactionManager.class)));
        assertEquals(0, writer.deleteVersion(Parent.class, 5L));
        assertEquals(0, writer.insertBatch(Collections.singletonList(parent("p")), 1L, 2L, Parent::getChildren, "SIX_INSTRU_ID"));
    }

    @Test
    public void columnName_nullAnnotationValue_usesFieldName() throws Exception {
        java.lang.reflect.Field f = Base.class.getDeclaredField("id");
        assertEquals("id", ReflectionTestUtils.invokeMethod(SixJdbcBulkWriter.TableMapping.class, "columnName", null, f));
    }

    // ------------------------------------------------------------------------------------------ mapping errors

    private void mappingFails(Class<?> type, String message) {
        try {
            writer.deleteVersion(type, 1L);
            fail();
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(message));
        }
    }

    @Test
    public void mappingErrors() {
        mappingFails(NoTable.class, "has no @Table");
        mappingFails(EmptyTable.class, "has no @Table");
        mappingFails(NoId.class, "has no @Id field");
        mappingFails(Unmapped.class, "Cannot map Unmapped.plain");
        mappingFails(RelationWithoutJoin.class, "Cannot map RelationWithoutJoin.related");
        mappingFails(NoGetter.class, "has no public getA()");
        mappingFails(IfaceEntity.class, "has no @Id field");
        mappingFails(BadName.class, "Unsupported SQL name 'BAD NAME'");
    }
}
