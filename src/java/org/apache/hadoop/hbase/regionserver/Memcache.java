/**
 * Copyright 2008 The Apache Software Foundation
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
 
package org.apache.hadoop.hbase.regionserver;

import java.io.IOException;
import java.rmi.UnexpectedException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HStoreKey;
import org.apache.hadoop.hbase.io.Cell;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.WritableComparable;


/**
 * The Memcache holds in-memory modifications to the HRegion.
 * Keeps a current map.  When asked to flush the map, current map is moved
 * to snapshot and is cleared.  We continue to serve edits out of new map
 * and backing snapshot until flusher reports in that the flush succeeded. At
 * this point we let the snapshot go.
 */
class Memcache {
  private final Log LOG = LogFactory.getLog(this.getClass().getName());
  
  // Note that since these structures are always accessed with a lock held,
  // so no additional synchronization is required.
  
  // The currently active sorted map of edits.
  private volatile SortedMap<HStoreKey, byte[]> memcache =
    createSynchronizedSortedMap();
  
  // Snapshot of memcache.  Made for flusher.
  private volatile SortedMap<HStoreKey, byte[]> snapshot =
    createSynchronizedSortedMap();

  private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();


  /*
   * Utility method.
   * @return sycnhronized sorted map of HStoreKey to byte arrays.
   */
  private static SortedMap<HStoreKey, byte[]> createSynchronizedSortedMap() {
    return Collections.synchronizedSortedMap(new TreeMap<HStoreKey, byte []>());
  }

  /**
   * Creates a snapshot of the current Memcache or returns existing snapshot.
   * Must be followed by a call to {@link #clearSnapshot(SortedMap)}
   * @return Snapshot. Never null.  May have no entries.
   */
  SortedMap<HStoreKey, byte[]> snapshot() {
    this.lock.writeLock().lock();
    try {
      // If snapshot has entries, then flusher failed or didn't call cleanup.
      if (this.snapshot.size() > 0) {
        LOG.debug("Returning existing snapshot. Either the snapshot was run " +
          "by the region -- normal operation but to be fixed -- or there is " +
          "another ongoing flush or did we fail last attempt?");
        return this.snapshot;
      }
      // We used to synchronize on the memcache here but we're inside a
      // write lock so removed it. Comment is left in case removal was a
      // mistake. St.Ack
      if (this.memcache.size() != 0) {
        this.snapshot = this.memcache;
        this.memcache = createSynchronizedSortedMap();
      }
      return this.snapshot;
    } finally {
      this.lock.writeLock().unlock();
    }
  }
  
  /**
   * Return the current snapshot.
   * @return Return snapshot.
   * @see {@link #snapshot()}
   * @see {@link #clearSnapshot(SortedMap)}
   */
  SortedMap<HStoreKey, byte[]> getSnapshot() {
    return this.snapshot;
  }

  /**
   * The passed snapshot was successfully persisted; it can be let go.
   * @param ss The snapshot to clean out.
   * @throws UnexpectedException
   * @see {@link #snapshot()}
   */
  void clearSnapshot(final SortedMap<HStoreKey, byte []> ss)
  throws UnexpectedException {
    this.lock.writeLock().lock();
    try {
      if (this.snapshot != ss) {
        throw new UnexpectedException("Current snapshot is " +
          this.snapshot + ", was passed " + ss);
      }
      // OK. Passed in snapshot is same as current snapshot.  If not-empty,
      // create a new snapshot and let the old one go.
      if (ss.size() != 0) {
        this.snapshot = createSynchronizedSortedMap();
      }
    } finally {
      this.lock.writeLock().unlock();
    }
  }

  /**
   * Write an update
   * @param key
   * @param value
   */
  void add(final HStoreKey key, final byte[] value) {
    this.lock.readLock().lock();
    try {
      this.memcache.put(key, value);
    } finally {
      this.lock.readLock().unlock();
    }
  }

  /**
   * Look back through all the backlog TreeMaps to find the target.
   * @param key
   * @param numVersions
   * @return An array of byte arrays ordered by timestamp.
   */
  List<Cell> get(final HStoreKey key, final int numVersions) {
    this.lock.readLock().lock();
    try {
      List<Cell> results;
      // The synchronizations here are because internalGet iterates
      synchronized (this.memcache) {
        results = internalGet(this.memcache, key, numVersions);
      }
      synchronized (this.snapshot) {
        results.addAll(results.size(),
          internalGet(this.snapshot, key, numVersions - results.size()));
      }
      return results;
    } finally {
      this.lock.readLock().unlock();
    }
  }
  
  /**
   * @param a
   * @param b
   * @return Return lowest of a or b or null if both a and b are null
   */
  @SuppressWarnings("unchecked")
  private WritableComparable getLowest(final WritableComparable a,
      final WritableComparable b) {
    if (a == null) {
      return b;
    }
    if (b == null) {
      return a;
    }
    return a.compareTo(b) <= 0? a: b;
  }

  /**
   * @param row Find the row that comes after this one.
   * @return Next row or null if none found
   */
  Text getNextRow(final Text row) {
    this.lock.readLock().lock();
    try {
      return (Text)getLowest(getNextRow(row, this.memcache),
        getNextRow(row, this.snapshot));
    } finally {
      this.lock.readLock().unlock();
    }
  }
  
  /*
   * @param row Find row that follows this one.
   * @param map Map to look in for a row beyond <code>row</code>.
   * This method synchronizes on passed map while iterating it.
   * @return Next row or null if none found.
   */
  private Text getNextRow(final Text row,
      final SortedMap<HStoreKey, byte []> map) {
    Text result = null;
    // Synchronize on the map to make the tailMap making 'safe'.
    synchronized (map) {
      // Make an HSK with maximum timestamp so we get past most of the current
      // rows cell entries.
      HStoreKey hsk = new HStoreKey(row, HConstants.LATEST_TIMESTAMP);
      SortedMap<HStoreKey, byte []> tailMap = map.tailMap(hsk);
      // Iterate until we fall into the next row; i.e. move off current row
      for (Map.Entry<HStoreKey, byte []> es: tailMap.entrySet()) {
        HStoreKey itKey = es.getKey();
        if (itKey.getRow().compareTo(row) <= 0) {
          continue;
        }
        // Note: Not suppressing deletes.
        result = itKey.getRow();
        break;
      }
    }
    return result;
  }

  /**
   * Return all the available columns for the given key.  The key indicates a 
   * row and timestamp, but not a column name.
   * @param key
   * @param columns Pass null for all columns else the wanted subset.
   * @param deletes Map to accumulate deletes found.
   * @param results Where to stick row results found.
   */
  void getFull(HStoreKey key, Set<Text> columns, Map<Text, Long> deletes, 
    Map<Text, Cell> results) {
    this.lock.readLock().lock();
    try {
      // The synchronizations here are because internalGet iterates
      synchronized (this.memcache) {
        internalGetFull(this.memcache, key, columns, deletes, results);
      }
      synchronized (this.snapshot) {
        internalGetFull(this.snapshot, key, columns, deletes, results);
      }
    } finally {
      this.lock.readLock().unlock();
    }
  }

  private void internalGetFull(SortedMap<HStoreKey, byte[]> map, HStoreKey key, 
    Set<Text> columns, Map<Text, Long> deletes, Map<Text, Cell> results) {

    if (map.isEmpty() || key == null) {
      return;
    }

    SortedMap<HStoreKey, byte[]> tailMap = map.tailMap(key);
    for (Map.Entry<HStoreKey, byte []> es: tailMap.entrySet()) {
      HStoreKey itKey = es.getKey();
      Text itCol = itKey.getColumn();
      if (results.get(itCol) == null && key.matchesWithoutColumn(itKey)) {
        byte [] val = tailMap.get(itKey);

        if (columns == null || columns.contains(itKey.getColumn())) {
          if (HLogEdit.isDeleted(val)) {
            if (!deletes.containsKey(itCol) 
              || deletes.get(itCol).longValue() < itKey.getTimestamp()) {
              deletes.put(new Text(itCol), Long.valueOf(itKey.getTimestamp()));
            }
          } else if (!(deletes.containsKey(itCol) 
            && deletes.get(itCol).longValue() >= itKey.getTimestamp())) {
            results.put(new Text(itCol), new Cell(val, itKey.getTimestamp()));
          }
        }
      } else if (key.getRow().compareTo(itKey.getRow()) < 0) {
        break;
      }
    }
  }

  /**
   * @param row Row to look for.
   * @param candidateKeys Map of candidate keys (Accumulation over lots of
   * lookup over stores and memcaches)
   */
  void getRowKeyAtOrBefore(final Text row, 
    SortedMap<HStoreKey, Long> candidateKeys) {
    this.lock.readLock().lock();
    try {
      synchronized (memcache) {
        internalGetRowKeyAtOrBefore(memcache, row, candidateKeys);
      }
      synchronized (snapshot) {
        internalGetRowKeyAtOrBefore(snapshot, row, candidateKeys);
      }
    } finally {
      this.lock.readLock().unlock();
    }
  }

  private void internalGetRowKeyAtOrBefore(SortedMap<HStoreKey, byte []> map,
    Text key, SortedMap<HStoreKey, Long> candidateKeys) {
    
    HStoreKey strippedKey = null;
    
    // we want the earliest possible to start searching from
    HStoreKey search_key = candidateKeys.isEmpty() ? 
      new HStoreKey(key) : new HStoreKey(candidateKeys.firstKey().getRow());
        
    Iterator<HStoreKey> key_iterator = null;
    HStoreKey found_key = null;
    
    // get all the entries that come equal or after our search key
    SortedMap<HStoreKey, byte []> tailMap = map.tailMap(search_key);
    
    // if there are items in the tail map, there's either a direct match to
    // the search key, or a range of values between the first candidate key
    // and the ultimate search key (or the end of the cache)
    if (!tailMap.isEmpty() && tailMap.firstKey().getRow().compareTo(key) <= 0) {
      key_iterator = tailMap.keySet().iterator();

      // keep looking at cells as long as they are no greater than the 
      // ultimate search key and there's still records left in the map.
      do {
        found_key = key_iterator.next();
        if (found_key.getRow().compareTo(key) <= 0) {
          strippedKey = stripTimestamp(found_key);
          if (HLogEdit.isDeleted(tailMap.get(found_key))) {
            if (candidateKeys.containsKey(strippedKey)) {
              long bestCandidateTs = 
                candidateKeys.get(strippedKey).longValue();
              if (bestCandidateTs <= found_key.getTimestamp()) {
                candidateKeys.remove(strippedKey);
              }
            }
          } else {
            candidateKeys.put(strippedKey, 
              new Long(found_key.getTimestamp()));
          }
        }
      } while (found_key.getRow().compareTo(key) <= 0 
        && key_iterator.hasNext());
    } else {
      // the tail didn't contain any keys that matched our criteria, or was 
      // empty. examine all the keys that preceed our splitting point.
      SortedMap<HStoreKey, byte []> headMap = map.headMap(search_key);
      
      // if we tried to create a headMap and got an empty map, then there are
      // no keys at or before the search key, so we're done.
      if (headMap.isEmpty()) {
        return;
      }        
      
      // if there aren't any candidate keys at this point, we need to search
      // backwards until we find at least one candidate or run out of headMap.
      if (candidateKeys.isEmpty()) {
        HStoreKey[] cells = 
          headMap.keySet().toArray(new HStoreKey[headMap.keySet().size()]);
           
        Text lastRowFound = null;
        for(int i = cells.length - 1; i >= 0; i--) {
          HStoreKey thisKey = cells[i];
          
          // if the last row we found a candidate key for is different than
          // the row of the current candidate, we can stop looking.
          if (lastRowFound != null && !lastRowFound.equals(thisKey.getRow())) {
            break;
          }
          
          // if this isn't a delete, record it as a candidate key. also 
          // take note of the row of this candidate so that we'll know when
          // we cross the row boundary into the previous row.
          if (!HLogEdit.isDeleted(headMap.get(thisKey))) {
            lastRowFound = thisKey.getRow();
            candidateKeys.put(stripTimestamp(thisKey), 
              new Long(thisKey.getTimestamp()));
          }
        }
      } else {
        // if there are already some candidate keys, we only need to consider
        // the very last row's worth of keys in the headMap, because any 
        // smaller acceptable candidate keys would have caused us to start
        // our search earlier in the list, and we wouldn't be searching here.
        SortedMap<HStoreKey, byte[]> thisRowTailMap = 
          headMap.tailMap(new HStoreKey(headMap.lastKey().getRow()));
          
        key_iterator = thisRowTailMap.keySet().iterator();
  
        do {
          found_key = key_iterator.next();
          
          if (HLogEdit.isDeleted(thisRowTailMap.get(found_key))) {
            strippedKey = stripTimestamp(found_key);              
            if (candidateKeys.containsKey(strippedKey)) {
              long bestCandidateTs = 
                candidateKeys.get(strippedKey).longValue();
              if (bestCandidateTs <= found_key.getTimestamp()) {
                candidateKeys.remove(strippedKey);
              }
            }
          } else {
            candidateKeys.put(stripTimestamp(found_key), 
              Long.valueOf(found_key.getTimestamp()));
          }
        } while (key_iterator.hasNext());
      }
    }
  }
  
  static HStoreKey stripTimestamp(HStoreKey key) {
    return new HStoreKey(key.getRow(), key.getColumn());
  }
  
  /**
   * Examine a single map for the desired key.
   *
   * TODO - This is kinda slow.  We need a data structure that allows for 
   * proximity-searches, not just precise-matches.
   * 
   * @param map
   * @param key
   * @param numVersions
   * @return Ordered list of items found in passed <code>map</code>.  If no
   * matching values, returns an empty list (does not return null).
   */
  private ArrayList<Cell> internalGet(
      final SortedMap<HStoreKey, byte []> map, final HStoreKey key,
      final int numVersions) {

    ArrayList<Cell> result = new ArrayList<Cell>();
    // TODO: If get is of a particular version -- numVersions == 1 -- we
    // should be able to avoid all of the tailmap creations and iterations
    // below.
    SortedMap<HStoreKey, byte []> tailMap = map.tailMap(key);
    for (Map.Entry<HStoreKey, byte []> es: tailMap.entrySet()) {
      HStoreKey itKey = es.getKey();
      if (itKey.matchesRowCol(key)) {
        if (!HLogEdit.isDeleted(es.getValue())) { 
          result.add(new Cell(tailMap.get(itKey), itKey.getTimestamp()));
        }
      }
      if (numVersions > 0 && result.size() >= numVersions) {
        break;
      }
    }
    return result;
  }

  /**
   * Get <code>versions</code> keys matching the origin key's
   * row/column/timestamp and those of an older vintage
   * Default access so can be accessed out of {@link HRegionServer}.
   * @param origin Where to start searching.
   * @param versions How many versions to return. Pass
   * {@link HConstants.ALL_VERSIONS} to retrieve all.
   * @return Ordered list of <code>versions</code> keys going from newest back.
   * @throws IOException
   */
  List<HStoreKey> getKeys(final HStoreKey origin, final int versions) {
    this.lock.readLock().lock();
    try {
      List<HStoreKey> results;
      synchronized (memcache) {
        results = internalGetKeys(this.memcache, origin, versions);
      }
      synchronized (snapshot) {
        results.addAll(results.size(), internalGetKeys(snapshot, origin,
            versions == HConstants.ALL_VERSIONS ? versions :
              (versions - results.size())));
      }
      return results;
      
    } finally {
      this.lock.readLock().unlock();
    }
  }

  /*
   * @param origin Where to start searching.
   * @param versions How many versions to return. Pass
   * {@link HConstants.ALL_VERSIONS} to retrieve all.
   * @return List of all keys that are of the same row and column and of
   * equal or older timestamp.  If no keys, returns an empty List. Does not
   * return null.
   */
  private List<HStoreKey> internalGetKeys(final SortedMap<HStoreKey, byte []> map,
      final HStoreKey origin, final int versions) {

    List<HStoreKey> result = new ArrayList<HStoreKey>();
    SortedMap<HStoreKey, byte []> tailMap = map.tailMap(origin);
    for (Map.Entry<HStoreKey, byte []> es: tailMap.entrySet()) {
      HStoreKey key = es.getKey();
  
      // if there's no column name, then compare rows and timestamps
      if (origin.getColumn().toString().equals("")) {
        // if the current and origin row don't match, then we can jump
        // out of the loop entirely.
        if (!key.getRow().equals(origin.getRow())) {
          break;
        }
        // if the rows match but the timestamp is newer, skip it so we can
        // get to the ones we actually want.
        if (key.getTimestamp() > origin.getTimestamp()) {
          continue;
        }
      }
      else{ // compare rows and columns
        // if the key doesn't match the row and column, then we're done, since 
        // all the cells are ordered.
        if (!key.matchesRowCol(origin)) {
          break;
        }
      }

      if (!HLogEdit.isDeleted(es.getValue())) {
        result.add(key);
        if (versions != HConstants.ALL_VERSIONS && result.size() >= versions) {
          // We have enough results.  Return.
          break;
        }
      }
    }
    return result;
  }


  /**
   * @param key
   * @return True if an entry and its content is {@link HGlobals.deleteBytes}.
   * Use checking values in store. On occasion the memcache has the fact that
   * the cell has been deleted.
   */
  boolean isDeleted(final HStoreKey key) {
    return HLogEdit.isDeleted(this.memcache.get(key));
  }

  /**
   * @return a scanner over the keys in the Memcache
   */
  InternalScanner getScanner(long timestamp,
    Text targetCols[], Text firstRow)
  throws IOException {
    this.lock.readLock().lock();
    try {
      return new MemcacheScanner(timestamp, targetCols, firstRow);
    } finally {
      this.lock.readLock().unlock();
    }
  }

  //////////////////////////////////////////////////////////////////////////////
  // MemcacheScanner implements the InternalScanner.
  // It lets the caller scan the contents of the Memcache.
  //////////////////////////////////////////////////////////////////////////////

  private class MemcacheScanner extends HAbstractScanner {
    private Text currentRow;
    private Set<Text> columns = null;
    
    MemcacheScanner(final long timestamp, final Text targetCols[],
      final Text firstRow)
    throws IOException {
      // Call to super will create ColumnMatchers and whether this is a regex
      // scanner or not.  Will also save away timestamp.  Also sorts rows.
      super(timestamp, targetCols);
      this.currentRow = firstRow;
      // If we're being asked to scan explicit columns rather than all in 
      // a family or columns that match regexes, cache the sorted array of
      // columns.
      this.columns = null;
      if (!isWildcardScanner()) {
        this.columns = new HashSet<Text>();
        for (int i = 0; i < targetCols.length; i++) {
          this.columns.add(targetCols[i]);
        }
      }
    }

    @Override
    public boolean next(HStoreKey key, SortedMap<Text, byte []> results)
    throws IOException {
      if (this.scannerClosed) {
        return false;
      }
      Map<Text, Long> deletes = new HashMap<Text, Long>();
      // Catch all row results in here.  These results are ten filtered to
      // ensure they match column name regexes, or if none, added to results.
      Map<Text, Cell> rowResults = new HashMap<Text, Cell>();
      if (results.size() > 0) {
        results.clear();
      }
      while (results.size() <= 0 &&
          (this.currentRow = getNextRow(this.currentRow)) != null) {
        if (deletes.size() > 0) {
          deletes.clear();
        }
        if (rowResults.size() > 0) {
          rowResults.clear();
        }
        key.setRow(this.currentRow);
        key.setVersion(this.timestamp);
        getFull(key, isWildcardScanner()? null: this.columns, deletes, rowResults);
        for (Map.Entry<Text, Cell> e: rowResults.entrySet()) {
          Text column = e.getKey();
          Cell c = e.getValue();
          if (isWildcardScanner()) {
            // Check the results match.  We only check columns, not timestamps.
            // We presume that timestamps have been handled properly when we
            // called getFull.
            if (!columnMatch(column)) {
              continue;
            }
          }
          results.put(column, c.getValue());
        }
      }
      return results.size() > 0;
    }

    public void close() {
      if (!scannerClosed) {
        scannerClosed = true;
      }
    }
  }
}
