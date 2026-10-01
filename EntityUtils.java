package com.bnpp.regliss.utils;

import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.persistence.Column;
import javax.persistence.Lob;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@SuppressWarnings("unchecked")
public final class EntityUtils {

    private static Logger logger = LoggerFactory.getLogger(EntityUtils.class);

    private static final Map<Class<Entity<?>>, List<Field>> CACHED_FIELDS = new ConcurrentHashMap<>();
    private static final int CACHED_FIELDS_MAX_SIZE = 1_000;

    private static final Charset UTF8_CHARSET = StandardCharsets.UTF_8;

    private EntityUtils(){}

    /**
     * Calculate equality between two entities.
     *
     * <p>
     * This method implements the typical rules of object equality by comparing
     * all fields that have been annotated with {@link EqualityField}. If no
     * fields are annotated as such {@link Entity} <i>id</i> will be used for
     * comparison.
     *
     * <p>
     * The following constraints do apply:
     * <ul>
     * <li>An object must be equal to itself.</li>
     * <li>An object can never be equal to null.</li>
     * <li>If two objects are equal their hash codes should be equal as well.
     * </li>
     * <li>Transient fields must not be included for equality checking.</li>
     * <li>Static fields are not included for equality checking.</li>
     * </ul>
     *
     * @param <P>
     *            The primary Key type
     * @param entity
     *            the entity to compare
     * @param other
     *            the other object to compare the entity to.
     * @return true if entities were found equal, false of not.
     */
    public static <P extends Serializable> boolean isEqual(Entity<P> entity, Object other) {
        // Don't call Entity.toSting() in this method or a method called by this
        // method.
        // this will produce a stackoverflowError!
        if (entity == null) {
            throw new IllegalArgumentException("Entity compared with must not be null");
        }

        // Null objects are not comparable and can never be equal.
        // To be comparable both objects should be of exactly the same type or a
        // CGLIB Hibernate proxy subclass of the entity class
        if (other == null || isComparableEntityType(entity, other)) {
            return false;
        }

        // Equal references means equal objects
        if (entity == other) { // NOPMD
            return true;
        }

        if (isEqualByID(entity, (Entity<P>) other)) {
            return true;
        } else {
            return equalsByEqualityFields(entity, other);
        }
    }

    public static <P extends Serializable> boolean equalsByEqualityFields(Entity<P> entity, Object other) {
        List<Field> fields = getEqualityFields((Class<Entity<?>>) entity.getClass());
        if (!fields.isEmpty()) {
            return isEqualByEqualityFields(entity, (Entity<P>) other, fields);
        } else {
            return true;
        }
    }

    public static <P extends Serializable> Map<String, Object> toStringEqualityFields(Entity<P> entity) {
        List<Field> fields = getEqualityFields((Class<Entity<?>>) entity.getClass());
        Map<String, Object> fieldValues = new HashMap<>();
        for (Field field : fields) {
            fieldValues.put(field.getName(), ReflectionUtils.getFieldValue(field, entity));
        }
        return fieldValues;
    }

    private static <P extends Serializable> boolean isComparableEntityType(Entity<P> entity, Object other) {
        return !entity.getClass().equals(other.getClass()) && !entity.getClass().isAssignableFrom(other.getClass());
    }

    private static List<Field> getEqualityFields(Class<Entity<?>> entityClass) {
        List<Field> fields = CACHED_FIELDS.get(entityClass);
        if ( fields == null ) {
            fields = ReflectionUtils.getAnnotatedFields(entityClass, EqualityField.class);
            if (CACHED_FIELDS.size() > CACHED_FIELDS_MAX_SIZE) {
                throw new IllegalStateException("Too many cached fields");
            }
            CACHED_FIELDS.put(entityClass, fields);
        }
        return fields;
    }

    private static <P extends Serializable> boolean isEqualByEqualityFields(Entity<P> entity, Entity<P> other,
            List<Field> fields) {

        if (logger.isTraceEnabled()) {
            logger.trace("Comparing Equality based on following fields:{}" , fields);
        }
        for (Field field : fields) {
            if (isValidEqualityField(field, entity) && !equalFields(field, entity, other)) {
                return false;
            }
        }
        return true;
    }

    private static <P extends Serializable> boolean isEqualByID(Entity<P> o1, Entity<P> o2) {
        // Comparing entities by id
        if (o1.getId() == null || o2.getId() == null) {
            return false;
        }
        return o1.getId().equals(o2.getId());
    }

    private static boolean equalFields(Field field, Object o1, Object o2) {
        // Null fields are not comparable and can never be equal.
        Object o1fieldVal = ReflectionUtils.getFieldValueForComparison(field, o1);
        Object o2fieldVal = ReflectionUtils.getFieldValueForComparison(field, o2);
        if (logger.isTraceEnabled()) {
            logger.trace("Comparing field values for Field: {}: {} <=> {}" , field, o1fieldVal,o2fieldVal);
        }
        return null == o1fieldVal && null == o2fieldVal || o1fieldVal != null && o1fieldVal.equals(o2fieldVal);
    }

    /**
     * Calculate the hash code of an entity type which extends {@link Entity}.
     * All fields annotated with {@link EqualityField} will be included for the
     * hash code calculation. If no such fields were found the {@link Entity}
     * <i>id</i> field will be used.
     *
     * @param <P>
     *            the primary key
     * @param entity
     *            the entity
     * @return the hash code
     */
    public static <P extends Serializable> int calculateHashCode(Entity<P> entity) {
        if (entity == null) {
            throw new IllegalArgumentException("Cannot calculate hashcode from null");
        }
        HashCodeBuilder builder = new HashCodeBuilder(7, 31);
        List<Field> fields = ReflectionUtils.getAnnotatedFields(entity.getClass(), EqualityField.class);
        if (fields.isEmpty()) { // If no fields were annotated explicitly, use
                                // Entity's id
            if (entity.getId() == null) {
                throw new IllegalStateException(
                        "Cannot calculate hashcode for instance of " + entity.getClass().getSimpleName()
                                + ": Id is not set and no fields are annotated with EqualityField");
            } else {
                builder.append(entity.getId());
            }
        } else {
            for (Field field : fields) {
                if (isValidEqualityField(field, entity)) {
                    Object value = ReflectionUtils.getFieldValueForComparison(field, entity);
                    try {
                        builder.append(value);
                    } catch (RuntimeException e) {
                        throw new IllegalArgumentException("Could not compute hash of field " +
                                entity.getClass().getSimpleName() + "."+field.getName(), e);
                    }
                }
            }
        }

        return builder.toHashCode();
    }

    private static boolean isValidEqualityField(Field field, Object o) {
        if (ReflectionUtils.isTransientField(field)) {
            throw new IllegalArgumentException("Transient field [" + field + "] declared in class [" + o.getClass()
                    + "] must not be use for equality testing !");
        }
        if (ReflectionUtils.isStaticField(field)) {
            logger.warn("Static field [{}] in class [{}] should not be used for equality testing.",field ,o.getClass());
            return false;
        }
        return true;
    }

    /** ! Only use in tests ! */
    public static <P extends Serializable> void setTestId___(Entity<P> entity, P newId) {
        ReflectionUtils.setFieldValue(ReflectionUtils.getField(entity.getClass(), "id"), entity, newId);
    }


    public static Method getChildAddMethod(Class<?> parentClass, Class<?> childClass) {
        for (Method method : parentClass.getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (method.getName().startsWith("add") && parameters.length == 1 && parameters[0].equals(childClass)) {
                return method;
            }
        }
        return null;
    }
    public static Method getChildRemoveMethod(Class<?> parentClass, Class<?> childClass) {
        for (Method method : parentClass.getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (method.getName().startsWith("remove") && parameters.length == 1 && parameters[0].equals(childClass)) {
                return method;
            }
        }
        return null;
    }

    public static Class<?> getElementType(Field collectionField) {
        try {
            ParameterizedType genericFieldType = (ParameterizedType) collectionField.getGenericType();
            return (Class<?>) genericFieldType.getActualTypeArguments()[0];
        } catch (Exception e) {
            throw new ReglissException(e, "Failed to get elementType for field " + collectionField);
        }
    }

    public static Optional<TruncatedRecordFieldsValues> truncateStringFields(Object entity) {
        List<TruncateStringFieldsVO> truncateStringFieldsList = new ArrayList<>();

        for (Field field : ReflectionUtils.getAnnotatedFields(entity.getClass(), Column.class)) {
            if (!String.class.equals(field.getType()) || field.getAnnotation(Lob.class) != null) {
                continue;
            }
            int maxLength = field.getAnnotation(Column.class).length();
            String originalValue = ReflectionUtils.getFieldValue(field, entity);
            if (originalValue != null && originalValue.getBytes(UTF8_CHARSET).length > maxLength) {
                String truncatedValue = truncateUtf8(originalValue, maxLength);
                logger.trace("Truncating field {}.{} from bytes={} to {}",
                        entity.getClass().getSimpleName(),
                        field.getName(),
                        originalValue.getBytes().length,
                        truncatedValue.getBytes().length);
                ReflectionUtils.setFieldValue(field, entity, truncatedValue);
                truncateStringFieldsList.add(new TruncateStringFieldsVO(field.getName(), originalValue, truncatedValue));
            }
        }
        if(truncateStringFieldsList.isEmpty()){
            return Optional.empty();
        } else {
            return Optional.of(new TruncatedRecordFieldsValues(entity.getClass().getName(), truncateStringFieldsList));
        }
    }

    // https://theholyjava.wordpress.com/2007/11/02/truncating-utf-string-to-the-given/
    public static String truncateUtf8(String string, int maxBytes) {
        CharsetDecoder cd = UTF8_CHARSET.newDecoder();
        byte[] sba = string.getBytes(UTF8_CHARSET);
        if (sba.length <= maxBytes) {
            return string;
        }
        // Ensure truncating by having byte buffer = DB_FIELD_LENGTH
        ByteBuffer bb = ByteBuffer.wrap(sba, 0, maxBytes); // len in [B]
        CharBuffer cb = CharBuffer.allocate(maxBytes); // len in [char] <= # [B]
        // Ignore an incomplete character
        cd.onMalformedInput(CodingErrorAction.IGNORE);
        cd.decode(bb, cb, true);
        cd.flush(cb);
        string = new String(cb.array(), 0, cb.position());
        return string;
    }

    public static Set<TruncatedRecordFieldsValues>  getTruncateStringFieldsEntityAndChildren(Record record){
        Set<TruncatedRecordFieldsValues> truncateStringFields = new HashSet<>();
        getTruncatedFields(record, record.getExternalReference())
                .ifPresent(truncateStringFields::add);

        for (Field field : ReflectionCachedUtils.getAnnotatedFields(record.getClass(), AuditedCollection.class)) {
            Method getter = ReflectionCachedUtils.getGetter(field);
            Set<AbstractSimpleEntity> children = (Set<AbstractSimpleEntity>) ReflectionUtils.invoke(record, getter);
            for(AbstractSimpleEntity child : children){
                getTruncatedFields(child, record.getExternalReference())
                        .ifPresent(truncateStringFields::add);
            }
        }
        return truncateStringFields;
    }

    private static Optional<TruncatedRecordFieldsValues> getTruncatedFields(Object entity, String recordExternalReference) {
        Optional<TruncatedRecordFieldsValues> tuncatedRecordFieldsValues = EntityUtils.truncateStringFields(entity);
        return tuncatedRecordFieldsValues.map(field -> field.setRecordExternalReference(recordExternalReference));
    }

    public static boolean isCaseInsensitiveField(Field field) {
        return field.getType().equals(String.class) && field.getAnnotation(CaseInsensitive.class) != null;
    }
}
