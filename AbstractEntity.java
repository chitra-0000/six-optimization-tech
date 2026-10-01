package com.bnpp.regliss.entity;

import java.io.Serializable;

/**
 * TODO: lines 1-26 of this file (imports and the start of this class comment) were not in the photo - copy them from the IDE.
 * implement the {@link #equals(Object)} and {@link #hashCode()} operations, or you could implement
 * the {@link #equals(Object)} and {@link #hashCode()} operation manually.
 *
 */
public abstract class AbstractEntity<PK  extends Serializable> implements Entity<PK> {

    /**
     * Calculate equality between to entities.
     *
     * <p>
     * This method implements the typical rules of object equality by comparing all fields that have
     * been annotated with {@link EqualityField}. If no fields are annotated as such
     * {@link AbstractEntity} <i>id</i> will be used for comparison.
     *
     * <p>
     * The following constraints do apply:
     * <ul>
     * <li>An object must be equal to itself.</li>
     * <li>An object can never be equal to null.</li>
     * <li>If two objects are equal their hash codes should be equal as well.</li>
     * <li>Transient fields must not be included for equality checking.</li>
     * <li>Static fields are not included for equality checking.</li>
     * </ul>
     *
     *
     * @param other the other object to compare the entity to.
     * @return true if entities were found equal, false of not.
     */
    @Override
    public boolean equals(Object other) { return EntityUtils.isEqual(this, other); }

    /**
     * Returns a hash code value for the object. All fields annotated with {@link EqualityField}
     * will be included for the hash code calculation. If no such fields were found the
     * {@link AbstractEntity} <i>id</i> field will be used.
     *
     * @return the hash code for this entity.
     */
    @Override
    public int hashCode() { return EntityUtils.calculateHashCode(this); }
}
