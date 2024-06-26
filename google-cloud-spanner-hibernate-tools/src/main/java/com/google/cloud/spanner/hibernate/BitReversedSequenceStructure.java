/*
 * Copyright 2019-2023 Google LLC
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA
 */

package com.google.cloud.spanner.hibernate;

import com.google.common.collect.Range;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import org.hibernate.boot.model.relational.AuxiliaryDatabaseObject;
import org.hibernate.boot.model.relational.Database;
import org.hibernate.boot.model.relational.Namespace;
import org.hibernate.boot.model.relational.QualifiedName;
import org.hibernate.boot.model.relational.Sequence;
import org.hibernate.boot.model.relational.SqlStringGenerationContext;
import org.hibernate.dialect.Dialect;
import org.hibernate.engine.jdbc.env.spi.JdbcEnvironment;
import org.hibernate.id.enhanced.SequenceStructure;

/**
 * This class generates a bit-reversed sequence for a Cloud Spanner database. It should be used in
 * combination with the {@link PooledBitReversedSequenceStyleGenerator}.
 */
public class BitReversedSequenceStructure extends SequenceStructure {

  private final String contributor;
  private final QualifiedName qualifiedSequenceName;
  private final int initialValue;
  private final List<Range<Long>> excludedRanges;

  /** Constructor for a new bit-reversed sequence structure. */
  public BitReversedSequenceStructure(
      JdbcEnvironment jdbcEnvironment,
      String contributor,
      QualifiedName qualifiedSequenceName,
      int initialValue,
      int incrementSize,
      List<Range<Long>> excludedRanges,
      Class numberType) {
    super(
        jdbcEnvironment,
        contributor,
        qualifiedSequenceName,
        initialValue,
        incrementSize,
        numberType);
    this.contributor = contributor;
    this.qualifiedSequenceName = qualifiedSequenceName;
    this.initialValue = initialValue;
    this.excludedRanges = excludedRanges;
  }

  private static String buildSkipRangeOptions(List<Range<Long>> excludeRanges) {
    if (excludeRanges.isEmpty()) {
      return "";
    }
    return String.format(
        " skip range %d %d", getMinSkipRange(excludeRanges), getMaxSkipRange(excludeRanges));
  }

  private static long getMinSkipRange(List<Range<Long>> excludeRanges) {
    return excludeRanges.stream().map(Range::lowerEndpoint).min(Long::compare).orElse(0L);
  }

  private static long getMaxSkipRange(List<Range<Long>> excludeRanges) {
    return excludeRanges.stream()
        .map(Range::upperEndpoint)
        .max(Long::compare)
        .orElse(Long.MAX_VALUE);
  }

  private static String buildStartCounterOption(int initialValue) {
    return initialValue == 1 ? "" : String.format(" start counter with %d", initialValue);
  }

  @Override
  protected void buildSequence(Database database) {
    Sequence sequence;
    Optional<AuxiliaryDatabaseObject> existing =
        database.getAuxiliaryDatabaseObjects().stream()
            .filter(aux -> aux.getExportIdentifier().equals(qualifiedSequenceName.render()))
            .findAny();
    if (existing.isPresent()) {
      sequence = ((BitReversedSequenceAuxiliaryDatabaseObject) existing.get()).sequence;
    } else {
      final Namespace namespace =
          database.locateNamespace(
              qualifiedSequenceName.getCatalogName(), qualifiedSequenceName.getSchemaName());
      sequence =
          namespace.createSequence(
              qualifiedSequenceName.getObjectName(),
              (physicalName) ->
                  new Sequence(
                      contributor,
                      namespace.getPhysicalName().getCatalog(),
                      namespace.getPhysicalName().getSchema(),
                      physicalName,
                      initialValue,
                      1));
      database.addAuxiliaryDatabaseObject(
          new BitReversedSequenceAuxiliaryDatabaseObject(sequence, excludedRanges));
      Iterator<Sequence> iterator = namespace.getSequences().iterator();
      while (iterator.hasNext()) {
        if (iterator.next() == sequence) {
          iterator.remove();
          break;
        }
      }
    }
    this.physicalSequenceName = sequence.getName();
  }

  static class BitReversedSequenceAuxiliaryDatabaseObject implements AuxiliaryDatabaseObject {

    private final Sequence sequence;

    private final List<Range<Long>> excludeRanges;

    BitReversedSequenceAuxiliaryDatabaseObject(Sequence sequence, List<Range<Long>> excludeRanges) {
      this.sequence = sequence;
      this.excludeRanges = excludeRanges;
    }

    @Override
    public boolean appliesToDialect(Dialect dialect) {
      return true;
    }

    @Override
    public boolean beforeTablesOnCreation() {
      return true;
    }

    @Override
    public String[] sqlCreateStrings(SqlStringGenerationContext context) {
      return new String[] {
        context
                .getDialect()
                .getSequenceSupport()
                .getCreateSequenceString(context.format(sequence.getName()))
            + " bit_reversed_positive"
            + buildSkipRangeOptions(excludeRanges)
            + buildStartCounterOption(sequence.getInitialValue())
      };
    }

    @Override
    public String[] sqlDropStrings(SqlStringGenerationContext context) {
      return context
          .getDialect()
          .getSequenceSupport()
          .getDropSequenceStrings(context.format(sequence.getName()));
    }

    @Override
    public String getExportIdentifier() {
      return sequence.getExportIdentifier();
    }
  }
}
