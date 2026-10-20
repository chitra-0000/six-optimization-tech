package com.bnpp.regliss.entity;

// TODO: re-add project imports (Alt+Enter / Optimize Imports) for the project classes used below

import org.junit.Test;

import java.time.LocalDateTime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

public class SixFilteredPollerTest {

    private static final LocalDateTime T = LocalDateTime.of(2026, 10, 4, 10, 15);

    @Test
    public void constructors_andAccessors() {
        SixFilteredPoller p = new SixFilteredPoller("INSTR", "READY", T, 1L, 2L, "951", 3L);
        assertEquals("INSTR", p.getFileType());
        assertEquals("READY", p.getStatus());
        assertEquals(T, p.getInsertionTime());
        assertEquals(T, p.getUpdatedTime());
        assertEquals(Long.valueOf(1L), p.getRawListId());
        assertEquals(Long.valueOf(2L), p.getRawVersionId());
        assertEquals("951", p.getSixListReference());
        assertEquals(Long.valueOf(3L), p.getBatchJobExecutionId());

        SixFilteredPoller plain = new SixFilteredPoller("STRUCT", T, 1L, 2L, "235", 3L);
        assertNull(plain.getStatus());
        assertNull(plain.getUpdatedTime());

        SixFilteredPoller empty = new SixFilteredPoller();
        empty.setId(5L);
        empty.setFileType("OPTIONS");
        empty.setStatus("DONE");
        empty.setInsertionTime(T);
        empty.setUpdatedTime(T);
        empty.setRawListId(6L);
        empty.setRawVersionId(7L);
        empty.setSixListReference("952");
        empty.setBatchJobExecutionId(8L);
        empty.setGenerationReason("REGENERATION");
        assertEquals(Long.valueOf(5L), empty.getId());
        assertEquals("REGENERATION", empty.getGenerationReason());
        assertEquals("SixFilteredPoller{id=5, fileType='OPTIONS', insertionTime='" + T + "', rawListId=6, rawVersionId=7,"
                + " sixListReference=952, batchJobExecutionId=8, status='DONE', updatedTime='" + T
                + "', generationReason='REGENERATION'}", empty.toString());
    }

    @Test
    public void equalsAndHashCode_byId() {
        SixFilteredPoller a = new SixFilteredPoller();
        a.setId(1L);
        SixFilteredPoller b = new SixFilteredPoller();
        b.setId(1L);
        SixFilteredPoller c = new SixFilteredPoller();
        c.setId(2L);
        assertEquals(a, a);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
        assertNotEquals(a, "a string");
        assertNotEquals(a, null);
    }
}
