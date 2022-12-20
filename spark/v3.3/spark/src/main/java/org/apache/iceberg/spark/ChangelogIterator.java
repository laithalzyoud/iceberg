/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iceberg.spark;

import java.io.Serializable;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import org.apache.iceberg.ChangelogOperation;
import org.apache.iceberg.relocated.com.google.common.collect.Iterators;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.catalyst.expressions.GenericInternalRow;
import org.apache.spark.sql.catalyst.expressions.GenericRowWithSchema;

/**
 * An iterator that transforms rows from changelog tables within a single Spark task. It assumes
 * that rows are sorted by identifier columns and change type.
 *
 * <p>It removes the carry-over rows. Carry-over rows are unchanged rows in a snapshot but showed as
 * delete-rows and insert-rows in a changelog table due to the copy-on-write(COW) mechanism. For
 * example, there are row1 (id=1, data='a') and row2 (id=2, data='b') in a data file, if we only
 * delete row2, the COW will copy row1 to a new data file and delete the old data file. The
 * changelog table will have two delete-rows(row1 and row2), and one insert-row(row1). Row1 is a
 * carry-over row.
 *
 * <p>The iterator marks the delete-row and insert-row to be the update-rows. For example, these two
 * rows
 *
 * <ul>
 *   <li>(id=1, data='a', op='DELETE')
 *   <li>(id=1, data='b', op='INSERT')
 * </ul>
 *
 * <p>will be marked as update-rows:
 *
 * <ul>
 *   <li>(id=1, data='a', op='UPDATE_BEFORE')
 *   <li>(id=1, data='b', op='UPDATE_AFTER')
 * </ul>
 */
public class ChangelogIterator implements Iterator<Row>, Serializable {
  private static final String DELETE = ChangelogOperation.DELETE.name();
  private static final String INSERT = ChangelogOperation.INSERT.name();
  private static final String UPDATE_BEFORE = ChangelogOperation.UPDATE_BEFORE.name();
  private static final String UPDATE_AFTER = ChangelogOperation.UPDATE_AFTER.name();

  private final Iterator<Row> rowIterator;
  private final int changeTypeIndex;
  private final List<Integer> identifierFieldIdx;

  private Row cachedRow = null;

  private ChangelogIterator(
      Iterator<Row> rowIterator, int changeTypeIndex, List<Integer> identifierFieldIdx) {
    this.rowIterator = rowIterator;
    this.changeTypeIndex = changeTypeIndex;
    this.identifierFieldIdx = identifierFieldIdx;
  }

  /**
   * Creates a new {@link ChangelogIterator} instance concatenated with the null-removal iterator.
   *
   * @param rowIterator the iterator of rows from a changelog table
   * @param changeTypeIndex the index of the change type column
   * @param identifierFieldIdx the indices of the identifier columns
   * @return a new {@link ChangelogIterator} instance concatenated with the null-removal iterator
   */
  public static Iterator<Row> iterator(
      Iterator<Row> rowIterator, int changeTypeIndex, List<Integer> identifierFieldIdx) {
    ChangelogIterator changelogIterator =
        new ChangelogIterator(rowIterator, changeTypeIndex, identifierFieldIdx);
    return Iterators.filter(changelogIterator, Objects::nonNull);
  }

  @Override
  public boolean hasNext() {
    if (cachedRow != null) {
      return true;
    }
    return rowIterator.hasNext();
  }

  @Override
  public Row next() {
    // if there is an updated cached row, return it directly
    if (cachedUpdateRecord(cachedRow)) {
      Row row = cachedRow;
      cachedRow = null;
      return row;
    }

    Row currentRow = currentRow();

    if (rowIterator.hasNext()) {
      GenericRowWithSchema nextRow = (GenericRowWithSchema) rowIterator.next();
      cachedRow = nextRow;

      if (isUpdateOrCarryoverRecord(currentRow, nextRow)) {
        if (isCarryoverRecord(currentRow, nextRow)) {
          // set carry-over rows to null for filtering out later
          currentRow = null;
          cachedRow = null;
        } else {
          Row[] rows = createUpdateChangelog((GenericRowWithSchema) currentRow, nextRow);
          currentRow = rows[0];
          cachedRow = rows[1];
        }
      }
    }

    return currentRow;
  }

  private Row[] createUpdateChangelog(
      GenericRowWithSchema currentRow, GenericRowWithSchema nextRow) {
    GenericInternalRow deletedRow = new GenericInternalRow(currentRow.values());
    GenericInternalRow insertedRow = new GenericInternalRow(nextRow.values());

    deletedRow.update(changeTypeIndex, UPDATE_BEFORE);
    insertedRow.update(changeTypeIndex, UPDATE_AFTER);

    return new Row[] {
      RowFactory.create(deletedRow.values()), RowFactory.create(insertedRow.values())
    };
  }

  private boolean isCarryoverRecord(Row currentRow, Row nextRow) {
    int length = currentRow.length();
    for (int i = 0; i < length; i++) {
      if (i == changeTypeIndex) {
        continue;
      }

      if (!isColumnSame(currentRow, nextRow, i)) {
        return false;
      }
    }

    return true;
  }

  private boolean cachedUpdateRecord(Row cachedRecord) {
    return cachedRecord != null
        && !cachedRecord.getString(changeTypeIndex).equals(DELETE)
        && !cachedRecord.getString(changeTypeIndex).equals(INSERT);
  }

  private Row currentRow() {
    if (cachedRow != null) {
      Row row = cachedRow;
      cachedRow = null;
      return row;
    } else {
      return rowIterator.next();
    }
  }

  private boolean isUpdateOrCarryoverRecord(Row currentRow, Row nextRow) {
    return sameLogicalRow(currentRow, nextRow)
        && currentRow.getString(changeTypeIndex).equals(DELETE)
        && nextRow.getString(changeTypeIndex).equals(INSERT);
  }

  private boolean sameLogicalRow(Row currentRow, Row nextRow) {
    for (int idx : identifierFieldIdx) {
      if (!isColumnSame(currentRow, nextRow, idx)) {
        return false;
      }
    }
    return true;
  }

  private static boolean isColumnSame(Row currentRow, Row nextRow, int idx) {
    if (currentRow.isNullAt(idx) && nextRow.isNullAt(idx)) {
      return true;
    }

    if (!currentRow.isNullAt(idx)
        && !nextRow.isNullAt(idx)
        && nextRow.get(idx).equals(currentRow.get(idx))) {
      return true;
    }

    return false;
  }
}
