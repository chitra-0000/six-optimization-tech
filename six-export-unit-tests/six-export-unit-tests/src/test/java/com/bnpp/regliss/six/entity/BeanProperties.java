package com.bnpp.regliss.six.entity;

import java.beans.BeanInfo;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;

/** Test helper: sets every read/write bean property of an object to a sample value and reads it back (Lombok accessors). */
public final class BeanProperties {

    private BeanProperties() {
    }

    /** @return the number of properties checked */
    public static int checkAll(Object bean) {
        try {
            BeanInfo info = Introspector.getBeanInfo(bean.getClass(), Object.class);
            int checked = 0;
            for (PropertyDescriptor p : info.getPropertyDescriptors()) {
                Method read = p.getReadMethod();
                Method write = p.getWriteMethod();
                if (read == null || write == null) {
                    continue;
                }
                Object value = sample(p.getPropertyType());
                write.invoke(bean, value);
                assertEquals(p.getName(), value, read.invoke(bean));
                checked++;
            }
            return checked;
        } catch (IntrospectionException | IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object sample(Class<?> type) {
        Map<Class<?>, Object> values = new HashMap<>();
        values.put(String.class, "v");
        values.put(Long.class, 7L);
        values.put(long.class, 7L);
        values.put(Integer.class, 3);
        values.put(int.class, 3);
        values.put(Boolean.class, Boolean.TRUE);
        values.put(boolean.class, true);
        values.put(Double.class, 1.5);
        values.put(double.class, 1.5);
        values.put(BigDecimal.class, BigDecimal.ONE);
        values.put(LocalDate.class, LocalDate.of(2026, 10, 4));
        values.put(LocalDateTime.class, LocalDateTime.of(2026, 10, 4, 10, 15));
        if (values.containsKey(type)) {
            return values.get(type);
        }
        if (type.isEnum()) {
            return type.getEnumConstants()[0];
        }
        if (List.class.equals(type)) {
            return new ArrayList<>();
        }
        if (Set.class.equals(type)) {
            return new HashSet<>();
        }
        if (Map.class.equals(type)) {
            return new HashMap<>();
        }
        if (!Modifier.isFinal(type.getModifiers()) && !type.isPrimitive()) {
            return mock(type);
        }
        return null;
    }
}
