/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package org.apache.fluo.accumulo.iterators;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;

import org.apache.accumulo.core.data.ByteSequence;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.PartialKey;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.iterators.IteratorEnvironment;
import org.apache.accumulo.core.iterators.SortedKeyValueIterator;
import org.apache.fluo.accumulo.util.ColumnType;
import org.apache.fluo.accumulo.util.ReadLockUtil;

import static org.apache.fluo.accumulo.util.ColumnConstants.TIMESTAMP_MASK;

public class OpenReadLockIterator implements SortedKeyValueIterator<Key, Value> {

  private TimestampSkippingIterator source;

  private Key lastDelete;

  private void findTop() throws IOException {
    while (source.hasTop()) {

      ColumnType colType = ColumnType.from(source.getTopKey());

      switch (colType) {
        case TX_DONE:
        case WRITE:
        case DEL_LOCK: {
          source.skipToPrefix(source.getTopKey(), ColumnType.RLOCK);
          break;
        }
        case RLOCK: {
          if (ReadLockUtil.isDelete(source.getTopKey())) {
            lastDelete.set(source.getTopKey());
          } else {
            if (lastDelete.equals(source.getTopKey(), PartialKey.ROW_COLFAM_COLQUAL_COLVIS)) {
              long ts1 = ReadLockUtil.decodeTs(source.getTopKey().getTimestamp() & TIMESTAMP_MASK);
              long ts2 = ReadLockUtil.decodeTs(lastDelete.getTimestamp() & TIMESTAMP_MASK);

              if (ts1 != ts2) {
                // found a read lock that is not suppressed by a delete read lock entry
                return;
              }
            } else {
              // found a read lock that is not suppressed by a delete read lock entry
              return;
            }
          }
          source.next();
          break;
        }
        case DATA:
        case LOCK:
        case ACK: {
          source.skipColumn(source.getTopKey());
          break;
        }
        default:
          throw new IllegalArgumentException("Unknown column type " + source.getTopKey());
      }
    }
  }

  @Override
  public void init(SortedKeyValueIterator<Key, Value> source, Map<String, String> options,
      IteratorEnvironment env) throws IOException {
    this.source = new TimestampSkippingIterator(source);
  }

  @Override
  public boolean hasTop() {
    return source.hasTop();
  }

  @Override
  public void next() throws IOException {
    source.next();
    findTop();
  }

  @Override
  public void seek(Range range, Collection<ByteSequence> columnFamilies, boolean inclusive)
      throws IOException {

    lastDelete = new Key();

    Collection<ByteSequence> fams;
    if (columnFamilies.isEmpty() && !inclusive) {
      fams = SnapshotIterator.NOTIFY_CF_SET;
      inclusive = false;
    } else {
      fams = columnFamilies;
    }

    source.seek(range, fams, inclusive);
    findTop();
  }

  @Override
  public Key getTopKey() {
    return source.getTopKey();
  }

  @Override
  public Value getTopValue() {
    return source.getTopValue();
  }

  @Override
  public SortedKeyValueIterator<Key, Value> deepCopy(IteratorEnvironment env) {
    throw new UnsupportedOperationException();
  }

}
