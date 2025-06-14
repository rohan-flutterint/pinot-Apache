/**
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
package org.apache.pinot.segment.local.upsert;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;
import org.apache.commons.io.FileUtils;
import org.apache.pinot.common.metrics.ServerMetrics;
import org.apache.pinot.common.utils.LLCSegmentName;
import org.apache.pinot.common.utils.UploadedRealtimeSegmentName;
import org.apache.pinot.segment.local.data.manager.TableDataManager;
import org.apache.pinot.segment.local.indexsegment.immutable.EmptyIndexSegment;
import org.apache.pinot.segment.local.indexsegment.immutable.ImmutableSegmentImpl;
import org.apache.pinot.segment.local.segment.readers.PinotSegmentColumnReader;
import org.apache.pinot.segment.local.upsert.ConcurrentMapPartitionUpsertMetadataManager.RecordLocation;
import org.apache.pinot.segment.local.utils.HashUtils;
import org.apache.pinot.segment.local.utils.WatermarkUtils;
import org.apache.pinot.segment.spi.ColumnMetadata;
import org.apache.pinot.segment.spi.IndexSegment;
import org.apache.pinot.segment.spi.MutableSegment;
import org.apache.pinot.segment.spi.V1Constants;
import org.apache.pinot.segment.spi.datasource.DataSource;
import org.apache.pinot.segment.spi.index.metadata.SegmentMetadataImpl;
import org.apache.pinot.segment.spi.index.mutable.ThreadSafeMutableRoaringBitmap;
import org.apache.pinot.segment.spi.index.reader.ForwardIndexReader;
import org.apache.pinot.spi.config.table.HashFunction;
import org.apache.pinot.spi.config.table.TableConfig;
import org.apache.pinot.spi.data.FieldSpec.DataType;
import org.apache.pinot.spi.data.Schema;
import org.apache.pinot.spi.data.readers.PrimaryKey;
import org.apache.pinot.spi.utils.ByteArray;
import org.apache.pinot.spi.utils.BytesUtils;
import org.apache.pinot.spi.utils.builder.TableNameBuilder;
import org.apache.pinot.util.TestUtils;
import org.mockito.MockedConstruction;
import org.roaringbitmap.buffer.MutableRoaringBitmap;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;
import static org.testng.Assert.*;


public class ConcurrentMapPartitionUpsertMetadataManagerTest {
  private static final String RAW_TABLE_NAME = "testTable";
  private static final String REALTIME_TABLE_NAME = TableNameBuilder.REALTIME.tableNameWithType(RAW_TABLE_NAME);
  private static final List<String> PRIMARY_KEY_COLUMNS = Collections.singletonList("pk");
  private static final List<String> COMPARISON_COLUMNS = Collections.singletonList("timeCol");
  private static final String DELETE_RECORD_COLUMN = "deleteCol";
  private static final File INDEX_DIR =
      new File(FileUtils.getTempDirectory(), "ConcurrentMapPartitionUpsertMetadataManagerTest");

  private UpsertContext.Builder _contextBuilder;

  @BeforeClass
  public void setUp()
      throws IOException {
    FileUtils.forceMkdir(INDEX_DIR);
    ServerMetrics.register(mock(ServerMetrics.class));
  }

  @BeforeMethod
  public void setUpContextBuilder() {
    TableDataManager tableDataManager = mock(TableDataManager.class);
    when(tableDataManager.getTableDataDir()).thenReturn(INDEX_DIR);
    _contextBuilder = new UpsertContext.Builder()
        .setTableConfig(mock(TableConfig.class))
        .setSchema(mock(Schema.class))
        .setTableDataManager(tableDataManager)
        .setPrimaryKeyColumns(PRIMARY_KEY_COLUMNS)
        .setComparisonColumns(COMPARISON_COLUMNS);
  }

  @AfterClass
  public void tearDown()
      throws IOException {
    FileUtils.forceDelete(INDEX_DIR);
  }

  @Test
  public void testStartFinishOperation() {
    ConcurrentMapPartitionUpsertMetadataManager upsertMetadataManager =
        new ConcurrentMapPartitionUpsertMetadataManager(REALTIME_TABLE_NAME, 0, _contextBuilder.build());

    // Start 2 operations
    assertTrue(upsertMetadataManager.startOperation());
    assertTrue(upsertMetadataManager.startOperation());

    // Stop and close the metadata manager
    AtomicBoolean stopped = new AtomicBoolean();
    AtomicBoolean closed = new AtomicBoolean();
    // Avoid early finalization by not using Executors.newSingleThreadExecutor (java <= 20, JDK-8145304)
    ExecutorService executor = Executors.newFixedThreadPool(1);
    executor.submit(() -> {
      upsertMetadataManager.stop();
      stopped.set(true);
      try {
        upsertMetadataManager.close();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
      closed.set(true);
    });
    executor.shutdown();

    // Wait for metadata manager to be stopped
    TestUtils.waitForCondition(aVoid -> stopped.get(), 10_000L, "Failed to stop the metadata manager");

    // Metadata manager should block on close because there are 2 pending operations
    assertFalse(closed.get());

    // Starting new operation should fail because the metadata manager is already stopped
    assertFalse(upsertMetadataManager.startOperation());

    // Finish one operation
    upsertMetadataManager.finishOperation();

    // Metadata manager should still block on close because there is still 1 pending operation
    assertFalse(closed.get());

    // Finish the other operation
    upsertMetadataManager.finishOperation();

    // Metadata manager should be closed now
    TestUtils.waitForCondition(aVoid -> closed.get(), 10_000L, "Failed to close the metadata manager");
  }

  @Test
  public void testAddReplaceRemoveSegment()
      throws IOException {
    verifyAddReplaceRemoveSegment(HashFunction.NONE, false);
    verifyAddReplaceRemoveSegment(HashFunction.MD5, false);
    verifyAddReplaceRemoveSegment(HashFunction.MURMUR3, false);
    verifyAddReplaceRemoveSegment(HashFunction.NONE, true);
    verifyAddReplaceRemoveSegment(HashFunction.MD5, true);
    verifyAddReplaceRemoveSegment(HashFunction.MURMUR3, true);
  }

  @Test
  public void testRemoveExpiredPrimaryKeys()
      throws IOException {
    _contextBuilder.setEnableSnapshot(true).setMetadataTTL(30);
    verifyRemoveExpiredPrimaryKeys(new Integer(80), new Integer(120));
    verifyRemoveExpiredPrimaryKeys(new Float(80), new Float(120));
    verifyRemoveExpiredPrimaryKeys(new Double(80), new Double(120));
    verifyRemoveExpiredPrimaryKeys(new Long(80), new Long(120));
  }

  @Test
  public void testAddSegmentOutOfTTL()
      throws IOException {
    _contextBuilder.setEnableSnapshot(true).setMetadataTTL(30);
    verifyAddSegmentOutOfTTL(new Integer(80));
    verifyAddSegmentOutOfTTL(new Float(80));
    verifyAddSegmentOutOfTTL(new Double(80));
    verifyAddSegmentOutOfTTL(new Long(80));
    verifyAddMultipleSegmentsWithOneOutOfTTL();
    verifyAddSegmentOutOfTTLWithRecordDelete();
  }

  @Test
  public void testTTLWithNegativeComparisonValues()
      throws IOException {
    _contextBuilder.setEnableSnapshot(true).setMetadataTTL(30);
    ConcurrentMapPartitionUpsertMetadataManager upsertMetadataManager =
        new ConcurrentMapPartitionUpsertMetadataManager(REALTIME_TABLE_NAME, 0, _contextBuilder.build());
    Map<Object, ConcurrentMapPartitionUpsertMetadataManager.RecordLocation> recordLocationMap =
        upsertMetadataManager._primaryKeyToRecordLocationMap;

    // Add record to update largestSeenTimestamp, largest seen timestamp: comparisonValue
    ThreadSafeMutableRoaringBitmap validDocIds0 = new ThreadSafeMutableRoaringBitmap();
    MutableSegment segment0 = mockMutableSegment(1, validDocIds0, null);
    upsertMetadataManager.addRecord(segment0, new RecordInfo(makePrimaryKey(10), 1, -80, false));
    checkRecordLocationForTTL(recordLocationMap, 10, segment0, 1, -80, HashFunction.NONE);
    assertEquals(upsertMetadataManager.getWatermark(), -80);

    // add a segment with segmentEndTime = -200 so it will be skipped since it out-of-TTL
    int numRecords = 4;
    int[] primaryKeys = new int[]{0, 1, 2, 3};
    ThreadSafeMutableRoaringBitmap validDocIds1 = new ThreadSafeMutableRoaringBitmap();
    List<PrimaryKey> primaryKeys1 = getPrimaryKeyList(numRecords, primaryKeys);
    ImmutableSegmentImpl segment1 =
        mockImmutableSegmentWithEndTime(1, validDocIds1, null, primaryKeys1, COMPARISON_COLUMNS, -200, null);

    // load segment1.
    upsertMetadataManager.addSegment(segment1);
    assertEquals(recordLocationMap.size(), 1);
    checkRecordLocationForTTL(recordLocationMap, 10, segment0, 1, -80, HashFunction.NONE);
    assertEquals(upsertMetadataManager.getWatermark(), -80);

    // Stop the metadata manager
    upsertMetadataManager.stop();

    // Close the metadata manager
    upsertMetadataManager.close();
  }

  @Test
  public void testManageWatermark()
      throws IOException {
    ConcurrentMapPartitionUpsertMetadataManager upsertMetadataManager =
        new ConcurrentMapPartitionUpsertMetadataManager(REALTIME_TABLE_NAME, 0, _contextBuilder.build());

    double currentTimeMs = System.currentTimeMillis();
    WatermarkUtils.persistWatermark(currentTimeMs, upsertMetadataManager.getWatermarkFile());
    assertTrue(new File(INDEX_DIR, V1Constants.TTL_WATERMARK_TABLE_PARTITION + 0).exists());

    double watermark = WatermarkUtils.loadWatermark(upsertMetadataManager.getWatermarkFile(), -1);
    assertEquals(watermark, currentTimeMs);

    ImmutableSegmentImpl segment =
        mockImmutableSegmentWithEndTime(1, new ThreadSafeMutableRoaringBitmap(), null, new ArrayList<>(),
            COMPARISON_COLUMNS, new Double(currentTimeMs + 1024), new MutableRoaringBitmap());
    upsertMetadataManager.setWatermark(currentTimeMs + 1024);
    assertEquals(upsertMetadataManager.getWatermark(), currentTimeMs + 1024);

    // Stop the metadata manager
    upsertMetadataManager.stop();

    // Close the metadata manager
    upsertMetadataManager.close();
  }

  @Test
  public void testGetQueryableDocIds() {
    _contextBuilder.setDeleteRecordColumn(DELETE_RECORD_COLUMN);

    boolean[] deleteFlags1 = new boolean[]{false, false, false, true, true, false};
    int[] docIds1 = new int[]{2, 4, 5};
    MutableRoaringBitmap validDocIdsSnapshot1 = new MutableRoaringBitmap();
    validDocIdsSnapshot1.add(docIds1);
    MutableRoaringBitmap queryableDocIds1 = new MutableRoaringBitmap();
    queryableDocIds1.add(new int[]{2, 5});
    verifyGetQueryableDocIds(false, deleteFlags1, validDocIdsSnapshot1, queryableDocIds1);

    // all records are not deleted
    boolean[] deleteFlags2 = new boolean[]{false, false, false, false, false, false};
    int[] docIds2 = new int[]{2, 4, 5};
    MutableRoaringBitmap validDocIdsSnapshot2 = new MutableRoaringBitmap();
    validDocIdsSnapshot2.add(docIds2);
    MutableRoaringBitmap queryableDocIds2 = new MutableRoaringBitmap();
    queryableDocIds2.add(docIds2);
    verifyGetQueryableDocIds(false, deleteFlags2, validDocIdsSnapshot2, queryableDocIds2);

    // delete column has null values
    boolean[] deleteFlags3 = new boolean[]{false, false, false, false, false, false};
    int[] docIds3 = new int[]{2, 4, 5};
    MutableRoaringBitmap validDocIdsSnapshot3 = new MutableRoaringBitmap();
    validDocIdsSnapshot3.add(docIds3);
    MutableRoaringBitmap queryableDocIds3 = new MutableRoaringBitmap();
    queryableDocIds3.add(docIds3);
    verifyGetQueryableDocIds(true, deleteFlags3, validDocIdsSnapshot3, queryableDocIds3);

    // All records are deleted record.
    boolean[] deleteFlags4 = new boolean[]{true, true, true, true, true, true};
    int[] docIds4 = new int[]{2, 4, 5};
    MutableRoaringBitmap validDocIdsSnapshot4 = new MutableRoaringBitmap();
    validDocIdsSnapshot4.add(docIds4);
    MutableRoaringBitmap queryableDocIds4 = new MutableRoaringBitmap();
    verifyGetQueryableDocIds(false, deleteFlags4, validDocIdsSnapshot4, queryableDocIds4);
  }

  private void verifyAddReplaceRemoveSegment(HashFunction hashFunction, boolean enableSnapshot)
      throws IOException {
    ConcurrentMapPartitionUpsertMetadataManager upsertMetadataManager =
        new ConcurrentMapPartitionUpsertMetadataManager(REALTIME_TABLE_NAME, 0,
            _contextBuilder.setHashFunction(hashFunction).build());
    Map<Object, RecordLocation> recordLocationMap = upsertMetadataManager._primaryKeyToRecordLocationMap;
    Set<IndexSegment> trackedSegments = upsertMetadataManager._trackedSegments;

    // Add the first segment
    int numRecords = 6;
    int[] primaryKeys = new int[]{0, 1, 2, 0, 1, 0};
    int[] timestamps = new int[]{100, 100, 100, 80, 120, 100};
    ThreadSafeMutableRoaringBitmap validDocIds1 = new ThreadSafeMutableRoaringBitmap();
    List<PrimaryKey> primaryKeys1 = getPrimaryKeyList(numRecords, primaryKeys);
    ImmutableSegmentImpl segment1 = mockImmutableSegment(1, validDocIds1, null, primaryKeys1);
    List<RecordInfo> recordInfoList1;
    if (enableSnapshot) {
      // get recordInfo from validDocIdSnapshot.
      // segment1 snapshot: 0 -> {5, 100}, 1 -> {4, 120}, 2 -> {2, 100}
      int[] docIds1 = new int[]{2, 4, 5};
      MutableRoaringBitmap validDocIdsSnapshot1 = new MutableRoaringBitmap();
      validDocIdsSnapshot1.add(docIds1);
      recordInfoList1 = getRecordInfoList(validDocIdsSnapshot1, primaryKeys, timestamps, null);
    } else {
      // get recordInfo by iterating all records.
      recordInfoList1 = getRecordInfoList(numRecords, primaryKeys, timestamps, null);
    }
    upsertMetadataManager.addSegment(segment1, validDocIds1, null, recordInfoList1.iterator());
    trackedSegments.add(segment1);
    // segment1: 0 -> {5, 100}, 1 -> {4, 120}, 2 -> {2, 100}
    assertEquals(recordLocationMap.size(), 3);
    checkRecordLocation(recordLocationMap, 0, segment1, 5, 100, hashFunction);
    checkRecordLocation(recordLocationMap, 1, segment1, 4, 120, hashFunction);
    checkRecordLocation(recordLocationMap, 2, segment1, 2, 100, hashFunction);
    assertEquals(validDocIds1.getMutableRoaringBitmap().toArray(), new int[]{2, 4, 5});

    // Add the second segment
    numRecords = 5;
    primaryKeys = new int[]{0, 1, 2, 3, 0};
    timestamps = new int[]{100, 100, 120, 80, 80};
    ThreadSafeMutableRoaringBitmap validDocIds2 = new ThreadSafeMutableRoaringBitmap();
    ImmutableSegmentImpl segment2 =
        mockImmutableSegment(2, validDocIds2, null, getPrimaryKeyList(numRecords, primaryKeys));
    List<RecordInfo> recordInfoList2;
    if (enableSnapshot) {
      // get recordInfo from validDocIdSnapshot.
      // segment2 snapshot: 0 -> {0, 100}, 2 -> {2, 120}, 3 -> {3, 80}
      // segment1 snapshot: 1 -> {4, 120}
      MutableRoaringBitmap validDocIdsSnapshot2 = new MutableRoaringBitmap();
      validDocIdsSnapshot2.add(0, 2, 3);
      recordInfoList2 = getRecordInfoList(validDocIdsSnapshot2, primaryKeys, timestamps, null);
    } else {
      // get recordInfo by iterating all records.
      recordInfoList2 = getRecordInfoList(numRecords, primaryKeys, timestamps, null);
    }
    upsertMetadataManager.addSegment(segment2, validDocIds2, null, recordInfoList2.iterator());
    trackedSegments.add(segment2);

    // segment1: 1 -> {4, 120}
    // segment2: 0 -> {0, 100}, 2 -> {2, 120}, 3 -> {3, 80}
    assertEquals(recordLocationMap.size(), 4);
    checkRecordLocation(recordLocationMap, 0, segment2, 0, 100, hashFunction);
    checkRecordLocation(recordLocationMap, 1, segment1, 4, 120, hashFunction);
    checkRecordLocation(recordLocationMap, 2, segment2, 2, 120, hashFunction);
    checkRecordLocation(recordLocationMap, 3, segment2, 3, 80, hashFunction);
    assertEquals(validDocIds1.getMutableRoaringBitmap().toArray(), new int[]{4});
    assertEquals(validDocIds2.getMutableRoaringBitmap().toArray(), new int[]{0, 2, 3});

    // Add an empty segment
    EmptyIndexSegment emptySegment = mockEmptySegment(3);
    upsertMetadataManager.addSegment(emptySegment);
    // segment1: 1 -> {4, 120}
    // segment2: 0 -> {0, 100}, 2 -> {2, 120}, 3 -> {3, 80}
    assertEquals(recordLocationMap.size(), 4);
    checkRecordLocation(recordLocationMap, 0, segment2, 0, 100, hashFunction);
    checkRecordLocation(recordLocationMap, 1, segment1, 4, 120, hashFunction);
    checkRecordLocation(recordLocationMap, 2, segment2, 2, 120, hashFunction);
    checkRecordLocation(recordLocationMap, 3, segment2, 3, 80, hashFunction);
    assertEquals(validDocIds1.getMutableRoaringBitmap().toArray(), new int[]{4});
    assertEquals(validDocIds2.getMutableRoaringBitmap().toArray(), new int[]{0, 2, 3});

    // Replace (reload) the first segment
    ThreadSafeMutableRoaringBitmap newValidDocIds1 = new ThreadSafeMutableRoaringBitmap();
    ImmutableSegmentImpl newSegment1 = mockImmutableSegment(1, newValidDocIds1, null, primaryKeys1);
    upsertMetadataManager.replaceSegment(newSegment1, newValidDocIds1, null, recordInfoList1.iterator(), segment1);
    trackedSegments.add(newSegment1);
    trackedSegments.remove(segment1);
    // original segment1: 1 -> {4, 120} (not in the map)
    // segment2: 0 -> {0, 100}, 2 -> {2, 120}, 3 -> {3, 80}
    // new segment1: 1 -> {4, 120}
    assertEquals(recordLocationMap.size(), 4);
    checkRecordLocation(recordLocationMap, 0, segment2, 0, 100, hashFunction);
    checkRecordLocation(recordLocationMap, 1, newSegment1, 4, 120, hashFunction);
    checkRecordLocation(recordLocationMap, 2, segment2, 2, 120, hashFunction);
    checkRecordLocation(recordLocationMap, 3, segment2, 3, 80, hashFunction);
    assertEquals(validDocIds1.getMutableRoaringBitmap().toArray(), new int[]{4});
    assertEquals(validDocIds2.getMutableRoaringBitmap().toArray(), new int[]{0, 2, 3});
    assertEquals(newValidDocIds1.getMutableRoaringBitmap().toArray(), new int[]{4});

    // Remove the original segment1
    upsertMetadataManager.removeSegment(segment1);
    // segment2: 0 -> {0, 100}, 2 -> {2, 120}, 3 -> {3, 80}
    // new segment1: 1 -> {4, 120}
    assertEquals(recordLocationMap.size(), 4);
    checkRecordLocation(recordLocationMap, 0, segment2, 0, 100, hashFunction);
    checkRecordLocation(recordLocationMap, 1, newSegment1, 4, 120, hashFunction);
    checkRecordLocation(recordLocationMap, 2, segment2, 2, 120, hashFunction);
    checkRecordLocation(recordLocationMap, 3, segment2, 3, 80, hashFunction);
    assertEquals(validDocIds1.getMutableRoaringBitmap().toArray(), new int[]{4});
    assertEquals(validDocIds2.getMutableRoaringBitmap().toArray(), new int[]{0, 2, 3});
    assertEquals(newValidDocIds1.getMutableRoaringBitmap().toArray(), new int[]{4});

    // Remove the empty segment
    upsertMetadataManager.removeSegment(emptySegment);
    // segment2: 0 -> {0, 100}, 2 -> {2, 120}, 3 -> {3, 80}
    // new segment1: 1 -> {4, 120}
    assertEquals(recordLocationMap.size(), 4);
    checkRecordLocation(recordLocationMap, 0, segment2, 0, 100, hashFunction);
    checkRecordLocation(recordLocationMap, 1, newSegment1, 4, 120, hashFunction);
    checkRecordLocation(recordLocationMap, 2, segment2, 2, 120, hashFunction);
    checkRecordLocation(recordLocationMap, 3, segment2, 3, 80, hashFunction);
    assertEquals(validDocIds2.getMutableRoaringBitmap().toArray(), new int[]{0, 2, 3});
    assertEquals(newValidDocIds1.getMutableRoaringBitmap().toArray(), new int[]{4});

    // Remove segment2
    upsertMetadataManager.removeSegment(segment2);
    // segment2: 0 -> {0, 100}, 2 -> {2, 120}, 3 -> {3, 80} (not in the map)
    // new segment1: 1 -> {4, 120}
    assertEquals(recordLocationMap.size(), 1);
    checkRecordLocation(recordLocationMap, 1, newSegment1, 4, 120, hashFunction);
    assertEquals(validDocIds2.getMutableRoaringBitmap().toArray(), new int[]{0, 2, 3});
    assertEquals(newValidDocIds1.getMutableRoaringBitmap().toArray(), new int[]{4});
    assertEquals(trackedSegments, Collections.singleton(newSegment1));

    // Stop the metadata manager
    upsertMetadataManager.stop();

    // Remove new segment1, should be no-op
    upsertMetadataManager.removeSegment(newSegment1);
    // new segment1: 1 -> {4, 120}
    assertEquals(recordLocationMap.size(), 1);
    checkRecordLocation(recordLocationMap, 1, newSegment1, 4, 120, hashFunction);
    assertEquals(newValidDocIds1.getMutableRoaringBitmap().toArray(), new int[]{4});
    assertEquals(trackedSegments, Collections.singleton(newSegment1));

    // Close the metadata manager
    upsertMetadataManager.close();
  }

  @Test
  public void testAddReplaceRemoveSegmentWithRecordDelete()
      throws IOException {
    _contextBuilder.setDeleteRecordColumn(DELETE_RECORD_COLUMN);
    verifyAddReplaceRemoveSegmentWithRecordDelete(HashFunction.NONE, false);
    verifyAddReplaceRemoveSegmentWithRecordDelete(HashFunction.MD5, false);
    verifyAddReplaceRemoveSegmentWithRecordDelete(HashFunction.MURMUR3, false);
    verifyAddReplaceRemoveSegmentWithRecordDelete(HashFunction.NONE, true);
    verifyAddReplaceRemoveSegmentWithRecordDelete(HashFunction.MD5, true);
    verifyAddReplaceRemoveSegmentWithRecordDelete(HashFunction.MURMUR3, true);
  }

  @Test
  public void verifyAddReplaceUploadedSegment1()
      throws IOException {
    ConcurrentMapPartitionUpsertMetadataManager upsertMetadataManager =
        new ConcurrentMapPartitionUpsertMetadataManager(REALTIME_TABLE_NAME, 0,
            _contextBuilder.setHashFunction(HashFunction.NONE).build());
    Map<Object, RecordLocation> recordLocationMap = upsertMetadataManager._primaryKeyToRecordLocationMap;
    Set<IndexSegment> trackedSegments = upsertMetadataManager._trackedSegments;

    // Add the first segment
    int numRecords = 6;
    int[] primaryKeys = new int[]{0, 1, 2, 0, 1, 0};
    int[] timestamps = new int[]{100, 100, 100, 80, 120, 100};
    ThreadSafeMutableRoaringBitmap validDocIds1 = new ThreadSafeMutableRoaringBitmap();
    List<PrimaryKey> primaryKeys1 = getPrimaryKeyList(numRecords, primaryKeys);
    SegmentMetadataImpl segmentMetadata = mock(SegmentMetadataImpl.class);
    when(segmentMetadata.getIndexCreationTime()).thenReturn(1000L);
    ImmutableSegmentImpl segment1 =
        mockImmutableSegmentWithSegmentMetadata(1, validDocIds1, null, primaryKeys1, segmentMetadata, null);
    List<RecordInfo> recordInfoList1;
    // get recordInfo by iterating all records.
    recordInfoList1 = getRecordInfoList(numRecords, primaryKeys, timestamps, null);
    upsertMetadataManager.addSegment(segment1, validDocIds1, null, recordInfoList1.iterator());
    trackedSegments.add(segment1);
    // segment1: 0 -> {5, 100}, 1 -> {4, 120}, 2 -> {2, 100}
    assertEquals(recordLocationMap.size(), 3);
    checkRecordLocation(recordLocationMap, 0, segment1, 5, 100, HashFunction.NONE);
    checkRecordLocation(recordLocationMap, 1, segment1, 4, 120, HashFunction.NONE);
    checkRecordLocation(recordLocationMap, 2, segment1, 2, 100, HashFunction.NONE);
    assertEquals(validDocIds1.getMutableRoaringBitmap().toArray(), new int[]{2, 4, 5});

    // Add the second segment of uploaded name format with same creation time
    numRecords = 2;
    primaryKeys = new int[]{0, 3};
    timestamps = new int[]{100, 80};
    ThreadSafeMutableRoaringBitmap validDocIds2 = new ThreadSafeMutableRoaringBitmap();
    ImmutableSegmentImpl uploadedSegment2 =
        mockUploadedImmutableSegment("2", validDocIds2, null, getPrimaryKeyList(numRecords, primaryKeys), 1000L);
    List<RecordInfo> recordInfoList2;
    // get recordInfo by iterating all records.
    recordInfoList2 = getRecordInfoList(numRecords, primaryKeys, timestamps, null);
    upsertMetadataManager.addSegment(uploadedSegment2, validDocIds2, null, recordInfoList2.iterator());
    trackedSegments.add(uploadedSegment2);

    // segment1: 1 -> {4, 120}, 2 -> {2, 100}
    // uploadedSegment2: 0 -> {0, 100}, 3 -> {1, 80}
    assertEquals(recordLocationMap.size(), 4);
    checkRecordLocation(recordLocationMap, 0, uploadedSegment2, 0, 100, HashFunction.NONE);
    checkRecordLocation(recordLocationMap, 1, segment1, 4, 120, HashFunction.NONE);
    checkRecordLocation(recordLocationMap, 2, segment1, 2, 100, HashFunction.NONE);
    checkRecordLocation(recordLocationMap, 3, uploadedSegment2, 1, 80, HashFunction.NONE);
    assertEquals(validDocIds1.getMutableRoaringBitmap().toArray(), new int[]{2, 4});
    assertEquals(validDocIds2.getMutableRoaringBitmap().toArray(), new int[]{0, 1});

    // replace uploadedSegment2
    ThreadSafeMutableRoaringBitmap newValidDocIds2 = new ThreadSafeMutableRoaringBitmap();
    ImmutableSegmentImpl newUploadedSegment2 =
        mockUploadedImmutableSegment("2", newValidDocIds2, null, getPrimaryKeyList(numRecords, primaryKeys), 1020L);
    upsertMetadataManager.replaceSegment(newUploadedSegment2, newValidDocIds2, null, recordInfoList2.iterator(),
        uploadedSegment2);
    trackedSegments.add(newUploadedSegment2);
    trackedSegments.remove(uploadedSegment2);

    // segment1: 1 -> {4, 120}, 2 -> {2, 100}
    // newUploadedSegment2: 0 -> {0, 100}, 3 -> {1, 80}
    assertEquals(recordLocationMap.size(), 4);
    checkRecordLocation(recordLocationMap, 0, newUploadedSegment2, 0, 100, HashFunction.NONE);
    checkRecordLocation(recordLocationMap, 1, segment1, 4, 120, HashFunction.NONE);
    checkRecordLocation(recordLocationMap, 2, segment1, 2, 100, HashFunction.NONE);
    checkRecordLocation(recordLocationMap, 3, newUploadedSegment2, 1, 80, HashFunction.NONE);
    assertEquals(validDocIds1.getMutableRoaringBitmap().toArray(), new int[]{2, 4});
    assertEquals(newValidDocIds2.getMutableRoaringBitmap().toArray(), new int[]{0, 1});

    // add upploadedSegment3 with higher creation time than newUploadedSegment2
    numRecords = 1;
    primaryKeys = new int[]{0};
    timestamps = new int[]{100};
    ThreadSafeMutableRoaringBitmap validDocIds3 = new ThreadSafeMutableRoaringBitmap();
    ImmutableSegmentImpl uploadedSegment3 =
        mockUploadedImmutableSegment("3", validDocIds3, null, getPrimaryKeyList(numRecords, primaryKeys), 1040L);
    List<RecordInfo> recordInfoList3;
    // get recordInfo by iterating all records.
    recordInfoList3 = getRecordInfoList(numRecords, primaryKeys, timestamps, null);
    upsertMetadataManager.addSegment(uploadedSegment3, validDocIds3, null, recordInfoList3.iterator());

    // segment1: 1 -> {4, 120}, 2 -> {2, 100}
    // newUploadedSegment2: 3 -> {1, 80}
    // uploadedSegment3: 0 -> {0, 100}
    assertEquals(recordLocationMap.size(), 4);
    checkRecordLocation(recordLocationMap, 0, uploadedSegment3, 0, 100, HashFunction.NONE);
    checkRecordLocation(recordLocationMap, 1, segment1, 4, 120, HashFunction.NONE);
    checkRecordLocation(recordLocationMap, 2, segment1, 2, 100, HashFunction.NONE);
    checkRecordLocation(recordLocationMap, 3, newUploadedSegment2, 1, 80, HashFunction.NONE);
    assertEquals(validDocIds1.getMutableRoaringBitmap().toArray(), new int[]{2, 4});
    assertEquals(newValidDocIds2.getMutableRoaringBitmap().toArray(), new int[]{1});
    assertEquals(validDocIds3.getMutableRoaringBitmap().toArray(), new int[]{0});

    // add uploadedSegment4 with higher creation time than segment 1 and same creation time as uploadedSegment3
    numRecords = 2;
    primaryKeys = new int[]{0, 1};
    timestamps = new int[]{100, 120};
    ThreadSafeMutableRoaringBitmap validDocIds4 = new ThreadSafeMutableRoaringBitmap();
    ImmutableSegmentImpl uploadedSegment4 =
        mockUploadedImmutableSegment("4", validDocIds4, null, getPrimaryKeyList(numRecords, primaryKeys), 1040L);
    List<RecordInfo> recordInfoList4;
    // get recordInfo by iterating all records.
    recordInfoList4 = getRecordInfoList(numRecords, primaryKeys, timestamps, null);
    upsertMetadataManager.addSegment(uploadedSegment4, validDocIds4, null, recordInfoList4.iterator());

    // segment1: 2 -> {2, 100}
    // newUploadedSegment2: 3 -> {1, 80}
    // uploadedSegment3: 0 -> {0, 100}
    // uploadedSegment4: 1 -> {1, 120}
    assertEquals(recordLocationMap.size(), 4);
    checkRecordLocation(recordLocationMap, 0, uploadedSegment3, 0, 100, HashFunction.NONE);
    checkRecordLocation(recordLocationMap, 1, uploadedSegment4, 1, 120, HashFunction.NONE);
    checkRecordLocation(recordLocationMap, 2, segment1, 2, 100, HashFunction.NONE);
    checkRecordLocation(recordLocationMap, 3, newUploadedSegment2, 1, 80, HashFunction.NONE);
    assertEquals(validDocIds1.getMutableRoaringBitmap().toArray(), new int[]{2});
    assertEquals(newValidDocIds2.getMutableRoaringBitmap().toArray(), new int[]{1});
    assertEquals(validDocIds3.getMutableRoaringBitmap().toArray(), new int[]{0});
    assertEquals(validDocIds4.getMutableRoaringBitmap().toArray(), new int[]{1});

    // remove segments
    upsertMetadataManager.removeSegment(segment1);
    upsertMetadataManager.removeSegment(uploadedSegment2);
    upsertMetadataManager.removeSegment(newUploadedSegment2);
    upsertMetadataManager.removeSegment(uploadedSegment3);
    upsertMetadataManager.removeSegment(uploadedSegment4);

    // Stop the metadata manager
    upsertMetadataManager.stop();

    // Close the metadata manager
    upsertMetadataManager.close();
  }

  private void verifyAddReplaceRemoveSegmentWithRecordDelete(HashFunction hashFunction, boolean enableSnapshot)
      throws IOException {
    ConcurrentMapPartitionUpsertMetadataManager upsertMetadataManager =
        new ConcurrentMapPartitionUpsertMetadataManager(REALTIME_TABLE_NAME, 0,
            _contextBuilder.setHashFunction(hashFunction).setEnableSnapshot(enableSnapshot).build());
    Map<Object, RecordLocation> recordLocationMap = upsertMetadataManager._primaryKeyToRecordLocationMap;
    Set<IndexSegment> trackedSegments = upsertMetadataManager._trackedSegments;

    // Add the first segment
    int numRecords = 6;
    int[] primaryKeys = new int[]{0, 1, 2, 0, 1, 0};
    int[] timestamps = new int[]{100, 100, 100, 80, 120, 100};
    boolean[] deleteFlags = new boolean[]{false, false, false, true, true, false};
    ThreadSafeMutableRoaringBitmap validDocIds1 = new ThreadSafeMutableRoaringBitmap();
    ThreadSafeMutableRoaringBitmap queryableDocIds1 = new ThreadSafeMutableRoaringBitmap();
    List<PrimaryKey> primaryKeys1 = getPrimaryKeyList(numRecords, primaryKeys);
    ImmutableSegmentImpl segment1 = mockImmutableSegment(1, validDocIds1, queryableDocIds1, primaryKeys1);
    List<RecordInfo> recordInfoList1;
    if (enableSnapshot) {
      // get recordInfo from validDocIdSnapshot.
      // segment1 snapshot: 0 -> {5, 100}, 1 -> {4, 120}, 2 -> {2, 100}
      int[] docIds1 = new int[]{2, 4, 5};
      MutableRoaringBitmap validDocIdsSnapshot1 = new MutableRoaringBitmap();
      validDocIdsSnapshot1.add(docIds1);
      recordInfoList1 = getRecordInfoList(validDocIdsSnapshot1, primaryKeys, timestamps, deleteFlags);
    } else {
      // get recordInfo by iterating all records.
      recordInfoList1 = getRecordInfoList(numRecords, primaryKeys, timestamps, deleteFlags);
    }
    upsertMetadataManager.addSegment(segment1, validDocIds1, queryableDocIds1, recordInfoList1.iterator());
    trackedSegments.add(segment1);
    // segment1: 0 -> {5, 100}, 1 -> {4, 120}, 2 -> {2, 100}
    assertEquals(recordLocationMap.size(), 3);
    checkRecordLocation(recordLocationMap, 0, segment1, 5, 100, hashFunction);
    checkRecordLocation(recordLocationMap, 1, segment1, 4, 120, hashFunction);
    checkRecordLocation(recordLocationMap, 2, segment1, 2, 100, hashFunction);
    assertEquals(validDocIds1.getMutableRoaringBitmap().toArray(), new int[]{2, 4, 5});
    assertEquals(queryableDocIds1.getMutableRoaringBitmap().toArray(), new int[]{2, 5});

    // Add the second segment
    numRecords = 5;
    primaryKeys = new int[]{0, 1, 2, 3, 0};
    timestamps = new int[]{100, 100, 120, 80, 80};
    deleteFlags = new boolean[]{false, true, true, false, false};
    ThreadSafeMutableRoaringBitmap validDocIds2 = new ThreadSafeMutableRoaringBitmap();
    ThreadSafeMutableRoaringBitmap queryableDocIds2 = new ThreadSafeMutableRoaringBitmap();
    ImmutableSegmentImpl segment2 =
        mockImmutableSegment(2, validDocIds2, queryableDocIds2, getPrimaryKeyList(numRecords, primaryKeys));
    List<RecordInfo> recordInfoList2;
    if (enableSnapshot) {
      // get recordInfo from validDocIdSnapshot.
      // segment2 snapshot: 0 -> {0, 100}, 2 -> {2, 120}, 3 -> {3, 80}
      // segment1 snapshot: 1 -> {4, 120}
      MutableRoaringBitmap validDocIdsSnapshot2 = new MutableRoaringBitmap();
      validDocIdsSnapshot2.add(0, 2, 3);
      recordInfoList2 = getRecordInfoList(validDocIdsSnapshot2, primaryKeys, timestamps, deleteFlags);
    } else {
      // get recordInfo by iterating all records.
      recordInfoList2 = getRecordInfoList(numRecords, primaryKeys, timestamps, deleteFlags);
    }
    upsertMetadataManager.addSegment(segment2, validDocIds2, queryableDocIds2, recordInfoList2.iterator());
    trackedSegments.add(segment2);

    // segment1: 1 -> {4, 120}
    // segment2: 0 -> {0, 100}, 2 -> {2, 120}, 3 -> {3, 80}
    assertEquals(recordLocationMap.size(), 4);
    checkRecordLocation(recordLocationMap, 0, segment2, 0, 100, hashFunction);
    checkRecordLocation(recordLocationMap, 1, segment1, 4, 120, hashFunction);
    checkRecordLocation(recordLocationMap, 2, segment2, 2, 120, hashFunction);
    checkRecordLocation(recordLocationMap, 3, segment2, 3, 80, hashFunction);
    assertEquals(validDocIds1.getMutableRoaringBitmap().toArray(), new int[]{4});
    assertEquals(validDocIds2.getMutableRoaringBitmap().toArray(), new int[]{0, 2, 3});
    assertTrue(queryableDocIds1.getMutableRoaringBitmap().isEmpty());
    assertEquals(queryableDocIds2.getMutableRoaringBitmap().toArray(), new int[]{0, 3});

    // Add an empty segment
    EmptyIndexSegment emptySegment = mockEmptySegment(3);
    upsertMetadataManager.addSegment(emptySegment);
    // segment1: 1 -> {4, 120}
    // segment2: 0 -> {0, 100}, 2 -> {2, 120}, 3 -> {3, 80}
    assertEquals(recordLocationMap.size(), 4);
    checkRecordLocation(recordLocationMap, 0, segment2, 0, 100, hashFunction);
    checkRecordLocation(recordLocationMap, 1, segment1, 4, 120, hashFunction);
    checkRecordLocation(recordLocationMap, 2, segment2, 2, 120, hashFunction);
    checkRecordLocation(recordLocationMap, 3, segment2, 3, 80, hashFunction);
    assertEquals(validDocIds1.getMutableRoaringBitmap().toArray(), new int[]{4});
    assertEquals(validDocIds2.getMutableRoaringBitmap().toArray(), new int[]{0, 2, 3});
    assertTrue(queryableDocIds1.getMutableRoaringBitmap().isEmpty());
    assertEquals(queryableDocIds2.getMutableRoaringBitmap().toArray(), new int[]{0, 3});

    // Replace (reload) the first segment
    ThreadSafeMutableRoaringBitmap newValidDocIds1 = new ThreadSafeMutableRoaringBitmap();
    ThreadSafeMutableRoaringBitmap newQueryableDocIds1 = new ThreadSafeMutableRoaringBitmap();
    ImmutableSegmentImpl newSegment1 = mockImmutableSegment(1, newValidDocIds1, newQueryableDocIds1, primaryKeys1);
    upsertMetadataManager.replaceSegment(newSegment1, newValidDocIds1, newQueryableDocIds1, recordInfoList1.iterator(),
        segment1);
    trackedSegments.add(newSegment1);
    trackedSegments.remove(segment1);

    // original segment1: 1 -> {4, 120} (not in the map)
    // segment2: 0 -> {0, 100}, 2 -> {2, 120}, 3 -> {3, 80}
    // new segment1: 1 -> {4, 120}
    assertEquals(recordLocationMap.size(), 4);
    checkRecordLocation(recordLocationMap, 0, segment2, 0, 100, hashFunction);
    checkRecordLocation(recordLocationMap, 1, newSegment1, 4, 120, hashFunction);
    checkRecordLocation(recordLocationMap, 2, segment2, 2, 120, hashFunction);
    checkRecordLocation(recordLocationMap, 3, segment2, 3, 80, hashFunction);
    assertEquals(validDocIds1.getMutableRoaringBitmap().toArray(), new int[]{4});
    assertEquals(validDocIds2.getMutableRoaringBitmap().toArray(), new int[]{0, 2, 3});
    assertEquals(newValidDocIds1.getMutableRoaringBitmap().toArray(), new int[]{4});
    assertTrue(queryableDocIds1.getMutableRoaringBitmap().isEmpty());
    assertEquals(queryableDocIds2.getMutableRoaringBitmap().toArray(), new int[]{0, 3});
    assertTrue(newQueryableDocIds1.getMutableRoaringBitmap().isEmpty());

    // Remove the original segment1
    upsertMetadataManager.removeSegment(segment1);
    // segment2: 0 -> {0, 100}, 2 -> {2, 120}, 3 -> {3, 80}
    // new segment1: 1 -> {4, 120}
    assertEquals(recordLocationMap.size(), 4);
    checkRecordLocation(recordLocationMap, 0, segment2, 0, 100, hashFunction);
    checkRecordLocation(recordLocationMap, 1, newSegment1, 4, 120, hashFunction);
    checkRecordLocation(recordLocationMap, 2, segment2, 2, 120, hashFunction);
    checkRecordLocation(recordLocationMap, 3, segment2, 3, 80, hashFunction);
    assertEquals(validDocIds1.getMutableRoaringBitmap().toArray(), new int[]{4});
    assertEquals(validDocIds2.getMutableRoaringBitmap().toArray(), new int[]{0, 2, 3});
    assertEquals(newValidDocIds1.getMutableRoaringBitmap().toArray(), new int[]{4});
    assertTrue(queryableDocIds1.getMutableRoaringBitmap().isEmpty());
    assertEquals(queryableDocIds2.getMutableRoaringBitmap().toArray(), new int[]{0, 3});
    assertTrue(newQueryableDocIds1.getMutableRoaringBitmap().isEmpty());

    // Remove the empty segment
    upsertMetadataManager.removeSegment(emptySegment);
    // segment2: 0 -> {0, 100}, 2 -> {2, 120}, 3 -> {3, 80}
    // new segment1: 1 -> {4, 120}
    assertEquals(recordLocationMap.size(), 4);
    checkRecordLocation(recordLocationMap, 0, segment2, 0, 100, hashFunction);
    checkRecordLocation(recordLocationMap, 1, newSegment1, 4, 120, hashFunction);
    checkRecordLocation(recordLocationMap, 2, segment2, 2, 120, hashFunction);
    checkRecordLocation(recordLocationMap, 3, segment2, 3, 80, hashFunction);
    assertEquals(validDocIds2.getMutableRoaringBitmap().toArray(), new int[]{0, 2, 3});
    assertEquals(newValidDocIds1.getMutableRoaringBitmap().toArray(), new int[]{4});
    assertEquals(queryableDocIds2.getMutableRoaringBitmap().toArray(), new int[]{0, 3});
    assertTrue(newQueryableDocIds1.getMutableRoaringBitmap().isEmpty());

    // Remove segment2
    upsertMetadataManager.removeSegment(segment2);
    // segment2: 0 -> {0, 100}, 2 -> {2, 120}, 3 -> {3, 80} (not in the map)
    // new segment1: 1 -> {4, 120}
    assertEquals(recordLocationMap.size(), 1);
    checkRecordLocation(recordLocationMap, 1, newSegment1, 4, 120, hashFunction);
    assertEquals(validDocIds2.getMutableRoaringBitmap().toArray(), new int[]{0, 2, 3});
    assertEquals(newValidDocIds1.getMutableRoaringBitmap().toArray(), new int[]{4});
    assertEquals(trackedSegments, Collections.singleton(newSegment1));
    assertEquals(queryableDocIds2.getMutableRoaringBitmap().toArray(), new int[]{0, 3});
    assertTrue(newQueryableDocIds1.getMutableRoaringBitmap().isEmpty());

    // Stop the metadata manager
    upsertMetadataManager.stop();

    // Remove new segment1, should be no-op
    upsertMetadataManager.removeSegment(newSegment1);
    // new segment1: 1 -> {4, 120}
    assertEquals(recordLocationMap.size(), 1);
    checkRecordLocation(recordLocationMap, 1, newSegment1, 4, 120, hashFunction);
    assertEquals(newValidDocIds1.getMutableRoaringBitmap().toArray(), new int[]{4});
    assertEquals(trackedSegments, Collections.singleton(newSegment1));
    assertTrue(newQueryableDocIds1.getMutableRoaringBitmap().isEmpty());

    // Close the metadata manager
    upsertMetadataManager.close();
  }

  private List<RecordInfo> getRecordInfoList(int numRecords, int[] primaryKeys, int[] timestamps,
      @Nullable boolean[] deleteRecordFlags) {
    List<RecordInfo> recordInfoList = new ArrayList<>();
    for (int i = 0; i < numRecords; i++) {
      recordInfoList.add(new RecordInfo(makePrimaryKey(primaryKeys[i]), i, new IntWrapper(timestamps[i]),
          deleteRecordFlags != null && deleteRecordFlags[i]));
    }
    return recordInfoList;
  }

  private List<RecordInfo> getRecordInfoListForTTL(int numRecords, int[] primaryKeys, int[] timestamps,
      @Nullable boolean[] deleteRecordFlags) {
    List<RecordInfo> recordInfoList = new ArrayList<>();
    for (int i = 0; i < numRecords; i++) {
      recordInfoList.add(new RecordInfo(makePrimaryKey(primaryKeys[i]), i, new Integer(timestamps[i]),
          deleteRecordFlags != null && deleteRecordFlags[i]));
    }
    return recordInfoList;
  }

  /**
   * Get recordInfo from validDocIdsSnapshot (enabledSnapshot = True).
   */
  private List<RecordInfo> getRecordInfoList(MutableRoaringBitmap validDocIdsSnapshot, int[] primaryKeys,
      int[] timestamps, @Nullable boolean[] deleteRecordFlags) {
    List<RecordInfo> recordInfoList = new ArrayList<>();
    Iterator<Integer> validDocIdsIterator = validDocIdsSnapshot.iterator();
    validDocIdsIterator.forEachRemaining((docId) -> recordInfoList.add(
        new RecordInfo(makePrimaryKey(primaryKeys[docId]), docId, new IntWrapper(timestamps[docId]),
            deleteRecordFlags != null && deleteRecordFlags[docId])));
    return recordInfoList;
  }

  private List<PrimaryKey> getPrimaryKeyList(int numRecords, int[] primaryKeys) {
    List<PrimaryKey> primaryKeyList = new ArrayList<>();
    for (int i = 0; i < numRecords; i++) {
      primaryKeyList.add(makePrimaryKey(primaryKeys[i]));
    }
    return primaryKeyList;
  }

  private static ImmutableSegmentImpl mockImmutableSegment(int sequenceNumber,
      ThreadSafeMutableRoaringBitmap validDocIds, @Nullable ThreadSafeMutableRoaringBitmap queryableDocIds,
      List<PrimaryKey> primaryKeys) {
    ImmutableSegmentImpl segment = mock(ImmutableSegmentImpl.class);
    when(segment.getSegmentName()).thenReturn(getSegmentName(sequenceNumber));
    when(segment.getValidDocIds()).thenReturn(validDocIds);
    when(segment.getQueryableDocIds()).thenReturn(queryableDocIds);
    DataSource dataSource = mock(DataSource.class);
    when(segment.getDataSource(anyString())).thenReturn(dataSource);
    ForwardIndexReader forwardIndex = mock(ForwardIndexReader.class);
    when(forwardIndex.isSingleValue()).thenReturn(true);
    when(forwardIndex.getStoredType()).thenReturn(DataType.INT);
    when(forwardIndex.getInt(anyInt(), any())).thenAnswer(
        invocation -> primaryKeys.get(invocation.getArgument(0)).getValues()[0]);
    when(dataSource.getForwardIndex()).thenReturn(forwardIndex);
    SegmentMetadataImpl segmentMetadata = mock(SegmentMetadataImpl.class);
    long creationTimeMs = System.currentTimeMillis();
    when(segmentMetadata.getIndexCreationTime()).thenReturn(creationTimeMs);
    when(segmentMetadata.getZkCreationTime()).thenReturn(creationTimeMs);
    when(segment.getSegmentMetadata()).thenReturn(segmentMetadata);
    return segment;
  }

  private static ImmutableSegmentImpl mockUploadedImmutableSegment(String suffix,
      ThreadSafeMutableRoaringBitmap validDocIds, @Nullable ThreadSafeMutableRoaringBitmap queryableDocIds,
      List<PrimaryKey> primaryKeys, Long creationTimeMs) {
    if (creationTimeMs == null) {
      creationTimeMs = System.currentTimeMillis();
    }
    ImmutableSegmentImpl segment = mock(ImmutableSegmentImpl.class);
    when(segment.getSegmentName()).thenReturn(getUploadedRealtimeSegmentName(creationTimeMs, suffix));
    when(segment.getValidDocIds()).thenReturn(validDocIds);
    when(segment.getQueryableDocIds()).thenReturn(queryableDocIds);
    DataSource dataSource = mock(DataSource.class);
    when(segment.getDataSource(anyString())).thenReturn(dataSource);
    ForwardIndexReader forwardIndex = mock(ForwardIndexReader.class);
    when(forwardIndex.isSingleValue()).thenReturn(true);
    when(forwardIndex.getStoredType()).thenReturn(DataType.INT);
    when(forwardIndex.getInt(anyInt(), any())).thenAnswer(
        invocation -> primaryKeys.get(invocation.getArgument(0)).getValues()[0]);
    when(dataSource.getForwardIndex()).thenReturn(forwardIndex);
    SegmentMetadataImpl segmentMetadata = mock(SegmentMetadataImpl.class);
    when(segmentMetadata.getIndexCreationTime()).thenReturn(creationTimeMs);
    when(segmentMetadata.getZkCreationTime()).thenReturn(creationTimeMs);
    when(segment.getSegmentMetadata()).thenReturn(segmentMetadata);
    return segment;
  }

  private static ImmutableSegmentImpl mockImmutableSegmentWithEndTime(int sequenceNumber,
      ThreadSafeMutableRoaringBitmap validDocIds, @Nullable ThreadSafeMutableRoaringBitmap queryableDocIds,
      List<PrimaryKey> primaryKeys, List<String> comparisonColumns, Comparable endTime, MutableRoaringBitmap snapshot) {
    ImmutableSegmentImpl segment = mockImmutableSegment(sequenceNumber, validDocIds, queryableDocIds, primaryKeys);
    SegmentMetadataImpl segmentMetadata = mock(SegmentMetadataImpl.class);
    when(segment.getSegmentMetadata()).thenReturn(segmentMetadata);
    ColumnMetadata columnMetadata = mock(ColumnMetadata.class);
    when(segmentMetadata.getColumnMetadataMap()).thenReturn(new TreeMap() {{
      this.put(comparisonColumns.get(0), columnMetadata);
    }});
    when(columnMetadata.getMaxValue()).thenReturn(endTime);
    if (snapshot != null) {
      when(segment.loadValidDocIdsFromSnapshot()).thenReturn(snapshot);
    } else {
      when(segment.loadValidDocIdsFromSnapshot()).thenReturn(validDocIds.getMutableRoaringBitmap());
    }
    return segment;
  }

  private static ImmutableSegmentImpl mockImmutableSegmentWithSegmentMetadata(int sequenceNumber,
      ThreadSafeMutableRoaringBitmap validDocIds, @Nullable ThreadSafeMutableRoaringBitmap queryableDocIds,
      List<PrimaryKey> primaryKeys, SegmentMetadataImpl segmentMetadata, MutableRoaringBitmap snapshot) {
    ImmutableSegmentImpl segment = mockImmutableSegment(sequenceNumber, validDocIds, queryableDocIds, primaryKeys);
    when(segment.getSegmentMetadata()).thenReturn(segmentMetadata);
    when(segment.loadValidDocIdsFromSnapshot()).thenReturn(snapshot);
    return segment;
  }

  private static EmptyIndexSegment mockEmptySegment(int sequenceNumber) {
    SegmentMetadataImpl segmentMetadata = mock(SegmentMetadataImpl.class);
    when(segmentMetadata.getName()).thenReturn(getSegmentName(sequenceNumber));
    return new EmptyIndexSegment(segmentMetadata);
  }

  private static MutableSegment mockMutableSegment(int sequenceNumber, ThreadSafeMutableRoaringBitmap validDocIds,
      ThreadSafeMutableRoaringBitmap queryableDocIds) {
    MutableSegment segment = mock(MutableSegment.class);
    when(segment.getSegmentName()).thenReturn(getSegmentName(sequenceNumber));
    when(segment.getQueryableDocIds()).thenReturn(queryableDocIds);
    when(segment.getValidDocIds()).thenReturn(validDocIds);
    return segment;
  }

  private static String getSegmentName(int sequenceNumber) {
    return new LLCSegmentName(RAW_TABLE_NAME, 0, sequenceNumber, System.currentTimeMillis()).toString();
  }

  private static String getUploadedRealtimeSegmentName(long creationTimeMs, String suffix) {
    return new UploadedRealtimeSegmentName(RAW_TABLE_NAME, 0, creationTimeMs, "uploaded", suffix).toString();
  }

  private static PrimaryKey makePrimaryKey(int value) {
    return new PrimaryKey(new Object[]{value});
  }

  private static void checkRecordLocation(Map<Object, RecordLocation> recordLocationMap, int keyValue,
      IndexSegment segment, int docId, int comparisonValue, HashFunction hashFunction) {
    RecordLocation recordLocation =
        recordLocationMap.get(HashUtils.hashPrimaryKey(makePrimaryKey(keyValue), hashFunction));
    assertNotNull(recordLocation);
    assertSame(recordLocation.getSegment(), segment);
    assertEquals(recordLocation.getDocId(), docId);
    assertEquals(((IntWrapper) recordLocation.getComparisonValue())._value, comparisonValue);
  }

  @Test
  public void testAddRecord()
      throws IOException {
    verifyAddRecord(HashFunction.NONE);
    verifyAddRecord(HashFunction.MD5);
    verifyAddRecord(HashFunction.MURMUR3);
  }

  private void verifyAddRecord(HashFunction hashFunction)
      throws IOException {
    ConcurrentMapPartitionUpsertMetadataManager upsertMetadataManager =
        new ConcurrentMapPartitionUpsertMetadataManager(REALTIME_TABLE_NAME, 0,
            _contextBuilder.setHashFunction(hashFunction).build());
    Map<Object, RecordLocation> recordLocationMap = upsertMetadataManager._primaryKeyToRecordLocationMap;

    // Add the first segment
    // segment1: 0 -> {0, 100}, 1 -> {1, 120}, 2 -> {2, 100}
    int numRecords = 3;
    int[] primaryKeys = new int[]{0, 1, 2};
    int[] timestamps = new int[]{100, 120, 100};
    ThreadSafeMutableRoaringBitmap validDocIds1 = new ThreadSafeMutableRoaringBitmap();
    ImmutableSegmentImpl segment1 =
        mockImmutableSegment(1, validDocIds1, null, getPrimaryKeyList(numRecords, primaryKeys));
    upsertMetadataManager.addSegment(segment1, validDocIds1, null,
        getRecordInfoList(numRecords, primaryKeys, timestamps, null).iterator());

    // Update records from the second segment
    ThreadSafeMutableRoaringBitmap validDocIds2 = new ThreadSafeMutableRoaringBitmap();
    MutableSegment segment2 = mockMutableSegment(1, validDocIds2, null);
    upsertMetadataManager.addRecord(segment2, new RecordInfo(makePrimaryKey(3), 0, new IntWrapper(100), false));

    // segment1: 0 -> {0, 100}, 1 -> {1, 120}, 2 -> {2, 100}
    // segment2: 3 -> {0, 100}
    checkRecordLocation(recordLocationMap, 0, segment1, 0, 100, hashFunction);
    checkRecordLocation(recordLocationMap, 1, segment1, 1, 120, hashFunction);
    checkRecordLocation(recordLocationMap, 2, segment1, 2, 100, hashFunction);
    checkRecordLocation(recordLocationMap, 3, segment2, 0, 100, hashFunction);
    assertEquals(validDocIds1.getMutableRoaringBitmap().toArray(), new int[]{0, 1, 2});
    assertEquals(validDocIds2.getMutableRoaringBitmap().toArray(), new int[]{0});

    upsertMetadataManager.addRecord(segment2, new RecordInfo(makePrimaryKey(2), 1, new IntWrapper(120), false));

    // segment1: 0 -> {0, 100}, 1 -> {1, 120}
    // segment2: 2 -> {1, 120}, 3 -> {0, 100}
    checkRecordLocation(recordLocationMap, 0, segment1, 0, 100, hashFunction);
    checkRecordLocation(recordLocationMap, 1, segment1, 1, 120, hashFunction);
    checkRecordLocation(recordLocationMap, 2, segment2, 1, 120, hashFunction);
    checkRecordLocation(recordLocationMap, 3, segment2, 0, 100, hashFunction);
    assertEquals(validDocIds1.getMutableRoaringBitmap().toArray(), new int[]{0, 1});
    assertEquals(validDocIds2.getMutableRoaringBitmap().toArray(), new int[]{0, 1});

    upsertMetadataManager.addRecord(segment2, new RecordInfo(makePrimaryKey(1), 2, new IntWrapper(100), false));

    // segment1: 0 -> {0, 100}, 1 -> {1, 120}
    // segment2: 2 -> {1, 120}, 3 -> {0, 100}
    checkRecordLocation(recordLocationMap, 0, segment1, 0, 100, hashFunction);
    checkRecordLocation(recordLocationMap, 1, segment1, 1, 120, hashFunction);
    checkRecordLocation(recordLocationMap, 2, segment2, 1, 120, hashFunction);
    checkRecordLocation(recordLocationMap, 3, segment2, 0, 100, hashFunction);
    assertEquals(validDocIds1.getMutableRoaringBitmap().toArray(), new int[]{0, 1});
    assertEquals(validDocIds2.getMutableRoaringBitmap().toArray(), new int[]{0, 1});

    upsertMetadataManager.addRecord(segment2, new RecordInfo(makePrimaryKey(0), 3, new IntWrapper(100), false));

    // segment1: 1 -> {1, 120}
    // segment2: 0 -> {3, 100}, 2 -> {1, 120}, 3 -> {0, 100}
    checkRecordLocation(recordLocationMap, 0, segment2, 3, 100, hashFunction);
    checkRecordLocation(recordLocationMap, 1, segment1, 1, 120, hashFunction);
    checkRecordLocation(recordLocationMap, 2, segment2, 1, 120, hashFunction);
    checkRecordLocation(recordLocationMap, 3, segment2, 0, 100, hashFunction);
    assertEquals(validDocIds1.getMutableRoaringBitmap().toArray(), new int[]{1});
    assertEquals(validDocIds2.getMutableRoaringBitmap().toArray(), new int[]{0, 1, 3});

    // Stop the metadata manager
    upsertMetadataManager.stop();

    // Add record should be no-op
    upsertMetadataManager.addRecord(segment2, new RecordInfo(makePrimaryKey(0), 4, new IntWrapper(120), false));
    // segment1: 1 -> {1, 120}
    // segment2: 0 -> {3, 100}, 2 -> {1, 120}, 3 -> {0, 100}
    checkRecordLocation(recordLocationMap, 0, segment2, 3, 100, hashFunction);
    checkRecordLocation(recordLocationMap, 1, segment1, 1, 120, hashFunction);
    checkRecordLocation(recordLocationMap, 2, segment2, 1, 120, hashFunction);
    checkRecordLocation(recordLocationMap, 3, segment2, 0, 100, hashFunction);
    assertEquals(validDocIds1.getMutableRoaringBitmap().toArray(), new int[]{1});
    assertEquals(validDocIds2.getMutableRoaringBitmap().toArray(), new int[]{0, 1, 3});

    // Close the metadata manager
    upsertMetadataManager.close();
  }

  @Test
  public void testAddOutOfOrderRecord()
      throws IOException {
    verifyAddOutOfOrderRecord(HashFunction.NONE);
    verifyAddOutOfOrderRecord(HashFunction.MD5);
    verifyAddOutOfOrderRecord(HashFunction.MURMUR3);
  }

  private void verifyAddOutOfOrderRecord(HashFunction hashFunction)
      throws IOException {
    ConcurrentMapPartitionUpsertMetadataManager upsertMetadataManager =
        new ConcurrentMapPartitionUpsertMetadataManager(REALTIME_TABLE_NAME, 0,
            _contextBuilder.setHashFunction(hashFunction).build());
    Map<Object, RecordLocation> recordLocationMap = upsertMetadataManager._primaryKeyToRecordLocationMap;

    // Add the first segment
    // segment1: 0 -> {0, 100}, 1 -> {1, 120}, 2 -> {2, 100}
    int numRecords = 3;
    int[] primaryKeys = new int[]{0, 1, 2};
    int[] timestamps = new int[]{100, 120, 100};
    ThreadSafeMutableRoaringBitmap validDocIds1 = new ThreadSafeMutableRoaringBitmap();
    ImmutableSegmentImpl segment1 =
        mockImmutableSegment(1, validDocIds1, null, getPrimaryKeyList(numRecords, primaryKeys));
    upsertMetadataManager.addSegment(segment1, validDocIds1, null,
        getRecordInfoList(numRecords, primaryKeys, timestamps, null).iterator());

    // Update records from the second segment
    ThreadSafeMutableRoaringBitmap validDocIds2 = new ThreadSafeMutableRoaringBitmap();
    MutableSegment segment2 = mockMutableSegment(1, validDocIds2, null);

    // new record, should return false for out of order event
    boolean isOutOfOrderRecord =
        !upsertMetadataManager.addRecord(segment2, new RecordInfo(makePrimaryKey(3), 0, new IntWrapper(100), false));
    assertFalse(isOutOfOrderRecord);

    // segment1: 0 -> {0, 100}, 1 -> {1, 120}, 2 -> {2, 100}
    // segment2: 3 -> {0, 100}
    checkRecordLocation(recordLocationMap, 0, segment1, 0, 100, hashFunction);
    checkRecordLocation(recordLocationMap, 1, segment1, 1, 120, hashFunction);
    checkRecordLocation(recordLocationMap, 2, segment1, 2, 100, hashFunction);
    checkRecordLocation(recordLocationMap, 3, segment2, 0, 100, hashFunction);
    assertEquals(validDocIds1.getMutableRoaringBitmap().toArray(), new int[]{0, 1, 2});
    assertEquals(validDocIds2.getMutableRoaringBitmap().toArray(), new int[]{0});

    // send an out-of-order event, should return true for orderness of event
    isOutOfOrderRecord =
        !upsertMetadataManager.addRecord(segment2, new RecordInfo(makePrimaryKey(2), 1, new IntWrapper(80), false));
    assertTrue(isOutOfOrderRecord);

    // ordered event for an existing key
    isOutOfOrderRecord =
        !upsertMetadataManager.addRecord(segment2, new RecordInfo(makePrimaryKey(2), 1, new IntWrapper(150), false));
    assertFalse(isOutOfOrderRecord);

    // segment1: 0 -> {0, 100}, 1 -> {1, 120}
    // segment2: 3 -> {0, 100}, 2 -> {1, 150}
    checkRecordLocation(recordLocationMap, 0, segment1, 0, 100, hashFunction);
    checkRecordLocation(recordLocationMap, 1, segment1, 1, 120, hashFunction);
    checkRecordLocation(recordLocationMap, 3, segment2, 0, 100, hashFunction);
    checkRecordLocation(recordLocationMap, 2, segment2, 1, 150, hashFunction);
    assertEquals(validDocIds1.getMutableRoaringBitmap().toArray(), new int[]{0, 1});
    assertEquals(validDocIds2.getMutableRoaringBitmap().toArray(), new int[]{0, 1});

    // Close the metadata manager
    upsertMetadataManager.stop();
    upsertMetadataManager.close();
  }

  @Test
  public void testPreloadSegment() {
    verifyPreloadSegment(HashFunction.NONE);
    verifyPreloadSegment(HashFunction.MD5);
    verifyPreloadSegment(HashFunction.MURMUR3);
  }

  private void verifyPreloadSegment(HashFunction hashFunction) {
    ConcurrentMapPartitionUpsertMetadataManager upsertMetadataManager =
        new ConcurrentMapPartitionUpsertMetadataManager(REALTIME_TABLE_NAME, 0,
            _contextBuilder.setHashFunction(hashFunction).build());
    Map<Object, RecordLocation> recordLocationMap = upsertMetadataManager._primaryKeyToRecordLocationMap;

    // Add the first segment
    // segment1: 0 -> {0, 100}, 1 -> {1, 120}, 2 -> {2, 100}
    int numRecords = 3;
    int[] primaryKeys = new int[]{0, 1, 2};
    int[] timestamps = new int[]{100, 120, 100};
    ThreadSafeMutableRoaringBitmap validDocIds1 = new ThreadSafeMutableRoaringBitmap();
    ImmutableSegmentImpl segment1 =
        mockImmutableSegment(1, validDocIds1, null, getPrimaryKeyList(numRecords, primaryKeys));
    // Preloading segment adds the segment without checking for upsert.
    upsertMetadataManager.doPreloadSegment(segment1, validDocIds1, null,
        getRecordInfoList(numRecords, primaryKeys, timestamps, null).iterator());

    // segment1: 0 -> {0, 100}, 1 -> {1, 120}, 2 -> {2, 100}
    checkRecordLocation(recordLocationMap, 0, segment1, 0, 100, hashFunction);
    checkRecordLocation(recordLocationMap, 1, segment1, 1, 120, hashFunction);
    checkRecordLocation(recordLocationMap, 2, segment1, 2, 100, hashFunction);
    assertEquals(validDocIds1.getMutableRoaringBitmap().toArray(), new int[]{0, 1, 2});

    // Add the 2nd segment
    // segment2: 0 -> {0, 1}, 1 -> {1, 2}
    numRecords = 2;
    primaryKeys = new int[]{0, 1};
    timestamps = new int[]{1, 2};
    ThreadSafeMutableRoaringBitmap validDocIds2 = new ThreadSafeMutableRoaringBitmap();
    ImmutableSegmentImpl segment2 =
        mockImmutableSegment(2, validDocIds2, null, getPrimaryKeyList(numRecords, primaryKeys));
    upsertMetadataManager.doPreloadSegment(segment2, validDocIds2, null,
        getRecordInfoList(numRecords, primaryKeys, timestamps, null).iterator());

    // segment1: 0 -> {0, 100}, 1 -> {1, 120}, 2 -> {2, 100}
    // segment2: 0 -> {0, 1}, 1 -> {1, 2}
    // segment2 was preloaded, so new locations got put in tracking map w/o checking on comparison values.
    checkRecordLocation(recordLocationMap, 0, segment2, 0, 1, hashFunction);
    checkRecordLocation(recordLocationMap, 1, segment2, 1, 2, hashFunction);
    checkRecordLocation(recordLocationMap, 2, segment1, 2, 100, hashFunction);
    assertEquals(validDocIds1.getMutableRoaringBitmap().toArray(), new int[]{0, 1, 2});
  }

  @Test
  public void testPreloadSegmentOutOfTTL() {
    _contextBuilder.setEnableSnapshot(true).setMetadataTTL(30);
    verifyPreloadSegmentOutOfTTL(HashFunction.NONE);
    verifyPreloadSegmentOutOfTTL(HashFunction.MD5);
    verifyPreloadSegmentOutOfTTL(HashFunction.MURMUR3);
  }

  private void verifyPreloadSegmentOutOfTTL(HashFunction hashFunction) {
    ConcurrentMapPartitionUpsertMetadataManager upsertMetadataManager =
        new ConcurrentMapPartitionUpsertMetadataManager(REALTIME_TABLE_NAME, 0,
            _contextBuilder.setHashFunction(hashFunction).build());
    Map<Object, RecordLocation> recordLocationMap = upsertMetadataManager._primaryKeyToRecordLocationMap;

    int numRecords = 3;
    int[] primaryKeys = new int[]{0, 1, 2};
    int[] docIds = new int[]{0, 1, 2};
    ThreadSafeMutableRoaringBitmap validDocIds = new ThreadSafeMutableRoaringBitmap();
    MutableRoaringBitmap snapshot = new MutableRoaringBitmap();
    snapshot.add(docIds);
    ImmutableSegmentImpl segment1 =
        mockImmutableSegmentWithEndTime(1, validDocIds, null, getPrimaryKeyList(numRecords, primaryKeys),
            COMPARISON_COLUMNS, 80, snapshot);

    upsertMetadataManager.setWatermark(60);
    upsertMetadataManager.doPreloadSegment(segment1);
    assertEquals(recordLocationMap.keySet().size(), 3);
    for (int key : primaryKeys) {
      assertTrue(recordLocationMap.containsKey(HashUtils.hashPrimaryKey(makePrimaryKey(key), hashFunction)),
          String.valueOf(key));
    }

    // Bump up the watermark, so that segment2 gets out of TTL and is skipped.
    upsertMetadataManager.setWatermark(120);
    primaryKeys = new int[]{10, 11, 12};
    ImmutableSegmentImpl segment2 =
        mockImmutableSegmentWithEndTime(1, validDocIds, null, getPrimaryKeyList(numRecords, primaryKeys),
            COMPARISON_COLUMNS, 80, snapshot);
    upsertMetadataManager.doPreloadSegment(segment2);
    assertEquals(recordLocationMap.keySet().size(), 3);
    for (int key : primaryKeys) {
      assertFalse(recordLocationMap.containsKey(HashUtils.hashPrimaryKey(makePrimaryKey(key), hashFunction)),
          String.valueOf(key));
    }
  }

  @Test
  public void testAddRecordWithDeleteColumn()
      throws IOException {
    _contextBuilder.setDeleteRecordColumn(DELETE_RECORD_COLUMN);
    verifyAddRecordWithDeleteColumn(HashFunction.NONE);
    verifyAddRecordWithDeleteColumn(HashFunction.MD5);
    verifyAddRecordWithDeleteColumn(HashFunction.MURMUR3);
  }

  private void verifyAddRecordWithDeleteColumn(HashFunction hashFunction)
      throws IOException {
    ConcurrentMapPartitionUpsertMetadataManager upsertMetadataManager =
        new ConcurrentMapPartitionUpsertMetadataManager(REALTIME_TABLE_NAME, 0,
            _contextBuilder.setHashFunction(hashFunction).build());
    Map<Object, RecordLocation> recordLocationMap = upsertMetadataManager._primaryKeyToRecordLocationMap;

    // queryableDocIds is same as validDocIds in the absence of delete markers
    // Add the first segment
    // segment1: 0 -> {0, 100}, 1 -> {1, 120}, 2 -> {2, 100}
    int numRecords = 3;
    int[] primaryKeys = new int[]{0, 1, 2};
    int[] timestamps = new int[]{100, 120, 100};
    ThreadSafeMutableRoaringBitmap validDocIds1 = new ThreadSafeMutableRoaringBitmap();
    ThreadSafeMutableRoaringBitmap queryableDocIds1 = new ThreadSafeMutableRoaringBitmap();
    ImmutableSegmentImpl segment1 =
        mockImmutableSegment(1, validDocIds1, queryableDocIds1, getPrimaryKeyList(numRecords, primaryKeys));
    upsertMetadataManager.addSegment(segment1, validDocIds1, queryableDocIds1,
        getRecordInfoList(numRecords, primaryKeys, timestamps, null).iterator());

    // Update records from the second segment
    ThreadSafeMutableRoaringBitmap validDocIds2 = new ThreadSafeMutableRoaringBitmap();
    ThreadSafeMutableRoaringBitmap queryableDocIds2 = new ThreadSafeMutableRoaringBitmap();
    MutableSegment segment2 = mockMutableSegment(1, validDocIds2, queryableDocIds2);
    upsertMetadataManager.addRecord(segment2, new RecordInfo(makePrimaryKey(3), 0, new IntWrapper(100), false));

    // segment1: 0 -> {0, 100}, 1 -> {1, 120}, 2 -> {2, 100}
    // segment2: 3 -> {0, 100}
    checkRecordLocation(recordLocationMap, 0, segment1, 0, 100, hashFunction);
    checkRecordLocation(recordLocationMap, 1, segment1, 1, 120, hashFunction);
    checkRecordLocation(recordLocationMap, 2, segment1, 2, 100, hashFunction);
    checkRecordLocation(recordLocationMap, 3, segment2, 0, 100, hashFunction);
    assertEquals(validDocIds1.getMutableRoaringBitmap().toArray(), new int[]{0, 1, 2});
    assertEquals(validDocIds2.getMutableRoaringBitmap().toArray(), new int[]{0});
    assertEquals(queryableDocIds1.getMutableRoaringBitmap().toArray(), new int[]{0, 1, 2});
    assertEquals(queryableDocIds2.getMutableRoaringBitmap().toArray(), new int[]{0});

    // Mark a record with latest value in segment1 as deleted
    upsertMetadataManager.addRecord(segment2, new RecordInfo(makePrimaryKey(2), 1, new IntWrapper(120), true));

    // segment1: 0 -> {0, 100}, 1 -> {1, 120}
    // segment2: 2 -> {1, 120}, 3 -> {0, 100}
    checkRecordLocation(recordLocationMap, 0, segment1, 0, 100, hashFunction);
    checkRecordLocation(recordLocationMap, 1, segment1, 1, 120, hashFunction);
    checkRecordLocation(recordLocationMap, 2, segment2, 1, 120, hashFunction);
    checkRecordLocation(recordLocationMap, 3, segment2, 0, 100, hashFunction);
    assertEquals(validDocIds1.getMutableRoaringBitmap().toArray(), new int[]{0, 1});
    assertEquals(validDocIds2.getMutableRoaringBitmap().toArray(), new int[]{0, 1});
    assertEquals(queryableDocIds1.getMutableRoaringBitmap().toArray(), new int[]{0, 1});
    assertEquals(queryableDocIds2.getMutableRoaringBitmap().toArray(), new int[]{0});

    // Mark a record with latest value in segment2 as deleted
    upsertMetadataManager.addRecord(segment2, new RecordInfo(makePrimaryKey(3), 2, new IntWrapper(150), true));

    // segment1: 0 -> {0, 100}, 1 -> {1, 120}
    // segment2: 2 -> {1, 120}, 3 -> {2, 150}
    checkRecordLocation(recordLocationMap, 0, segment1, 0, 100, hashFunction);
    checkRecordLocation(recordLocationMap, 1, segment1, 1, 120, hashFunction);
    checkRecordLocation(recordLocationMap, 2, segment2, 1, 120, hashFunction);
    checkRecordLocation(recordLocationMap, 3, segment2, 2, 150, hashFunction);
    assertEquals(validDocIds1.getMutableRoaringBitmap().toArray(), new int[]{0, 1});
    assertEquals(validDocIds2.getMutableRoaringBitmap().toArray(), new int[]{1, 2});
    assertEquals(queryableDocIds1.getMutableRoaringBitmap().toArray(), new int[]{0, 1});
    assertEquals(queryableDocIds2.getMutableRoaringBitmap().toArray(), new int[]{});

    // Revive a deleted primary key (by providing a larger comparisonValue)
    upsertMetadataManager.addRecord(segment2, new RecordInfo(makePrimaryKey(3), 3, new IntWrapper(200), false));

    // segment1: 0 -> {0, 100}, 1 -> {1, 120}
    // segment2: 2 -> {1, 120}, 3 -> {3, 200}
    checkRecordLocation(recordLocationMap, 0, segment1, 0, 100, hashFunction);
    checkRecordLocation(recordLocationMap, 1, segment1, 1, 120, hashFunction);
    checkRecordLocation(recordLocationMap, 2, segment2, 1, 120, hashFunction);
    checkRecordLocation(recordLocationMap, 3, segment2, 3, 200, hashFunction);
    assertEquals(validDocIds1.getMutableRoaringBitmap().toArray(), new int[]{0, 1});
    assertEquals(validDocIds2.getMutableRoaringBitmap().toArray(), new int[]{1, 3});
    assertEquals(queryableDocIds1.getMutableRoaringBitmap().toArray(), new int[]{0, 1});
    assertEquals(queryableDocIds2.getMutableRoaringBitmap().toArray(), new int[]{3});

    // Stop the metadata manager
    upsertMetadataManager.stop();

    // Add record should be no-op
    upsertMetadataManager.addRecord(segment2, new RecordInfo(makePrimaryKey(0), 4, new IntWrapper(120), false));
    // segment1: 0 -> {0, 100}, 1 -> {1, 120}
    // segment2: 2 -> {1, 120}, 3 -> {3, 200}
    checkRecordLocation(recordLocationMap, 0, segment1, 0, 100, hashFunction);
    checkRecordLocation(recordLocationMap, 1, segment1, 1, 120, hashFunction);
    checkRecordLocation(recordLocationMap, 2, segment2, 1, 120, hashFunction);
    checkRecordLocation(recordLocationMap, 3, segment2, 3, 200, hashFunction);
    assertEquals(validDocIds1.getMutableRoaringBitmap().toArray(), new int[]{0, 1});
    assertEquals(validDocIds2.getMutableRoaringBitmap().toArray(), new int[]{1, 3});
    assertEquals(queryableDocIds1.getMutableRoaringBitmap().toArray(), new int[]{0, 1});
    assertEquals(queryableDocIds2.getMutableRoaringBitmap().toArray(), new int[]{3});

    // Close the metadata manager
    upsertMetadataManager.close();
  }

  @Test
  public void testRemoveExpiredDeletedKeys()
      throws IOException {
    _contextBuilder.setDeleteRecordColumn(DELETE_RECORD_COLUMN).setDeletedKeysTTL(20);
    verifyRemoveExpiredDeletedKeys(HashFunction.NONE);
    verifyRemoveExpiredDeletedKeys(HashFunction.MD5);
    verifyRemoveExpiredDeletedKeys(HashFunction.MURMUR3);
  }

  private void verifyRemoveExpiredDeletedKeys(HashFunction hashFunction)
      throws IOException {
    ConcurrentMapPartitionUpsertMetadataManager upsertMetadataManager =
        new ConcurrentMapPartitionUpsertMetadataManager(REALTIME_TABLE_NAME, 0,
            _contextBuilder.setHashFunction(hashFunction).build());
    Map<Object, RecordLocation> recordLocationMap = upsertMetadataManager._primaryKeyToRecordLocationMap;

    // Add the first segment
    // segment1: 0 -> {0, 100}, 1 -> {1, 120}, 2 -> {2, 100}
    int numRecords = 3;
    int[] primaryKeys = new int[]{0, 1, 2};
    int[] timestamps = new int[]{100, 120, 100};
    ThreadSafeMutableRoaringBitmap validDocIds1 = new ThreadSafeMutableRoaringBitmap();
    ThreadSafeMutableRoaringBitmap queryableDocIds1 = new ThreadSafeMutableRoaringBitmap();
    ImmutableSegmentImpl segment1 =
        mockImmutableSegment(1, validDocIds1, queryableDocIds1, getPrimaryKeyList(numRecords, primaryKeys));
    upsertMetadataManager.addSegment(segment1, validDocIds1, queryableDocIds1,
        getRecordInfoListForTTL(numRecords, primaryKeys, timestamps, null).iterator());

    // Update records from the second segment
    ThreadSafeMutableRoaringBitmap validDocIds2 = new ThreadSafeMutableRoaringBitmap();
    ThreadSafeMutableRoaringBitmap queryableDocIds2 = new ThreadSafeMutableRoaringBitmap();
    MutableSegment segment2 = mockMutableSegment(1, validDocIds2, queryableDocIds2);
    upsertMetadataManager.addRecord(segment2, new RecordInfo(makePrimaryKey(3), 0, new Integer(100), false));

    // segment1: 0 -> {0, 100}, 1 -> {1, 120}, 2 -> {2, 100}
    // segment2: 3 -> {0, 100}
    checkRecordLocationForTTL(recordLocationMap, 0, segment1, 0, 100, hashFunction);
    checkRecordLocationForTTL(recordLocationMap, 1, segment1, 1, 120, hashFunction);
    checkRecordLocationForTTL(recordLocationMap, 2, segment1, 2, 100, hashFunction);
    checkRecordLocationForTTL(recordLocationMap, 3, segment2, 0, 100, hashFunction);
    assertEquals(validDocIds1.getMutableRoaringBitmap().toArray(), new int[]{0, 1, 2});
    assertEquals(validDocIds2.getMutableRoaringBitmap().toArray(), new int[]{0});
    assertEquals(queryableDocIds1.getMutableRoaringBitmap().toArray(), new int[]{0, 1, 2});
    assertEquals(queryableDocIds2.getMutableRoaringBitmap().toArray(), new int[]{0});

    // Mark a record with latest value in segment1 as deleted (outside TTL-window)
    upsertMetadataManager.addRecord(segment2, new RecordInfo(makePrimaryKey(2), 1, new Integer(120), true));

    // segment1: 0 -> {0, 100}, 1 -> {1, 120}
    // segment2: 2 -> {1, 120}, 3 -> {0, 100}
    checkRecordLocationForTTL(recordLocationMap, 0, segment1, 0, 100, hashFunction);
    checkRecordLocationForTTL(recordLocationMap, 1, segment1, 1, 120, hashFunction);
    checkRecordLocationForTTL(recordLocationMap, 2, segment2, 1, 120, hashFunction);
    checkRecordLocationForTTL(recordLocationMap, 3, segment2, 0, 100, hashFunction);
    assertEquals(validDocIds1.getMutableRoaringBitmap().toArray(), new int[]{0, 1});
    assertEquals(validDocIds2.getMutableRoaringBitmap().toArray(), new int[]{0, 1});
    assertEquals(queryableDocIds1.getMutableRoaringBitmap().toArray(), new int[]{0, 1});
    assertEquals(queryableDocIds2.getMutableRoaringBitmap().toArray(), new int[]{0});

    // Mark a record with latest value in segment2 as deleted (within TTL window)
    upsertMetadataManager.addRecord(segment2, new RecordInfo(makePrimaryKey(3), 2, new Integer(150), true));

    // segment1: 0 -> {0, 100}, 1 -> {1, 120}
    // segment2: 2 -> {1, 120}, 3 -> {2, 150}
    checkRecordLocationForTTL(recordLocationMap, 0, segment1, 0, 100, hashFunction);
    checkRecordLocationForTTL(recordLocationMap, 1, segment1, 1, 120, hashFunction);
    checkRecordLocationForTTL(recordLocationMap, 2, segment2, 1, 120, hashFunction);
    checkRecordLocationForTTL(recordLocationMap, 3, segment2, 2, 150, hashFunction);
    assertEquals(validDocIds1.getMutableRoaringBitmap().toArray(), new int[]{0, 1});
    assertEquals(validDocIds2.getMutableRoaringBitmap().toArray(), new int[]{1, 2});
    assertEquals(queryableDocIds1.getMutableRoaringBitmap().toArray(), new int[]{0, 1});
    assertEquals(queryableDocIds2.getMutableRoaringBitmap().toArray(), new int[]{});

    // delete-key segment2: 2 -> {1, 120}
    upsertMetadataManager.removeExpiredPrimaryKeys();
    // segment1: 0 -> {0, 100}, 1 -> {1, 120}
    // segment2: 3 -> {2, 150}
    checkRecordLocationForTTL(recordLocationMap, 0, segment1, 0, 100, hashFunction);
    checkRecordLocationForTTL(recordLocationMap, 1, segment1, 1, 120, hashFunction);
    assertEquals(validDocIds1.getMutableRoaringBitmap().toArray(), new int[]{0, 1});
    assertEquals(validDocIds2.getMutableRoaringBitmap().toArray(), new int[]{2});
    assertEquals(queryableDocIds1.getMutableRoaringBitmap().toArray(), new int[]{0, 1});
    assertEquals(queryableDocIds2.getMutableRoaringBitmap().toArray(), new int[]{});

    // Stop the metadata manager
    upsertMetadataManager.stop();

    // Close the metadata manager
    upsertMetadataManager.close();
  }

  private void verifyRemoveExpiredPrimaryKeys(Comparable earlierComparisonValue, Comparable largerComparisonValue)
      throws IOException {
    ConcurrentMapPartitionUpsertMetadataManager upsertMetadataManager =
        new ConcurrentMapPartitionUpsertMetadataManager(REALTIME_TABLE_NAME, 0, _contextBuilder.build());
    Map<Object, ConcurrentMapPartitionUpsertMetadataManager.RecordLocation> recordLocationMap =
        upsertMetadataManager._primaryKeyToRecordLocationMap;

    // Add record to update largestSeenTimestamp, largest seen timestamp: earlierComparisonValue
    ThreadSafeMutableRoaringBitmap validDocIds0 = new ThreadSafeMutableRoaringBitmap();
    MutableSegment segment0 = mockMutableSegment(1, validDocIds0, null);
    upsertMetadataManager.addRecord(segment0, new RecordInfo(makePrimaryKey(10), 1, earlierComparisonValue, false));
    checkRecordLocationForTTL(recordLocationMap, 10, segment0, 1, 80, HashFunction.NONE);
    assertEquals(upsertMetadataManager.getWatermark(), 80);

    // Add a segment with segmentEndTime = earlierComparisonValue, so it will not be skipped
    int numRecords = 4;
    int[] primaryKeys = new int[]{0, 1, 2, 3};
    Number[] timestamps = new Number[]{100, 100, 120, 80};
    ThreadSafeMutableRoaringBitmap validDocIds1 = new ThreadSafeMutableRoaringBitmap();
    List<PrimaryKey> primaryKeys1 = getPrimaryKeyList(numRecords, primaryKeys);
    ImmutableSegmentImpl segment1 =
        mockImmutableSegmentWithEndTime(1, validDocIds1, null, primaryKeys1, COMPARISON_COLUMNS, earlierComparisonValue,
            null);

    // load segment1.
    upsertMetadataManager.addSegment(segment1, validDocIds1, null,
        getRecordInfoListForTTL(numRecords, primaryKeys, timestamps).iterator());
    assertEquals(recordLocationMap.size(), 5);
    checkRecordLocationForTTL(recordLocationMap, 0, segment1, 0, 100, HashFunction.NONE);
    checkRecordLocationForTTL(recordLocationMap, 1, segment1, 1, 100, HashFunction.NONE);
    checkRecordLocationForTTL(recordLocationMap, 2, segment1, 2, 120, HashFunction.NONE);
    checkRecordLocationForTTL(recordLocationMap, 3, segment1, 3, 80, HashFunction.NONE);
    checkRecordLocationForTTL(recordLocationMap, 10, segment0, 1, 80, HashFunction.NONE);
    // The watermark is updated by segment's max comparison value, although segment1 has a few docs with larger
    // comparison values. This segment is created to simplify the tests here and it shouldn't exist in real world env.
    assertEquals(upsertMetadataManager.getWatermark(), 80);

    // Add record to update largestSeenTimestamp, largest seen timestamp: largerComparisonValue
    upsertMetadataManager.addRecord(segment0, new RecordInfo(makePrimaryKey(10), 0, largerComparisonValue, false));
    assertEquals(recordLocationMap.size(), 5);
    checkRecordLocationForTTL(recordLocationMap, 0, segment1, 0, 100, HashFunction.NONE);
    checkRecordLocationForTTL(recordLocationMap, 1, segment1, 1, 100, HashFunction.NONE);
    checkRecordLocationForTTL(recordLocationMap, 2, segment1, 2, 120, HashFunction.NONE);
    checkRecordLocationForTTL(recordLocationMap, 3, segment1, 3, 80, HashFunction.NONE);
    checkRecordLocationForTTL(recordLocationMap, 10, segment0, 0, 120, HashFunction.NONE);
    assertEquals(upsertMetadataManager.getWatermark(), 120);

    // records before (largest seen timestamp - TTL) are expired and removed from upsertMetadata.
    upsertMetadataManager.removeExpiredPrimaryKeys();
    assertEquals(recordLocationMap.size(), 4);
    checkRecordLocationForTTL(recordLocationMap, 0, segment1, 0, 100, HashFunction.NONE);
    checkRecordLocationForTTL(recordLocationMap, 1, segment1, 1, 100, HashFunction.NONE);
    checkRecordLocationForTTL(recordLocationMap, 2, segment1, 2, 120, HashFunction.NONE);
    checkRecordLocationForTTL(recordLocationMap, 10, segment0, 0, 120, HashFunction.NONE);
    assertEquals(upsertMetadataManager.getWatermark(), 120);

    // ValidDocIds for out-of-ttl records should not be removed.
    assertEquals(validDocIds1.getMutableRoaringBitmap().toArray(), new int[]{0, 1, 2, 3});

    // Stop the metadata manager
    upsertMetadataManager.stop();

    // Close the metadata manager
    upsertMetadataManager.close();
  }

  private void verifyAddMultipleSegmentsWithOneOutOfTTL()
      throws IOException {
    ConcurrentMapPartitionUpsertMetadataManager upsertMetadataManager =
        new ConcurrentMapPartitionUpsertMetadataManager(REALTIME_TABLE_NAME, 0, _contextBuilder.build());
    Map<Object, ConcurrentMapPartitionUpsertMetadataManager.RecordLocation> recordLocationMap =
        upsertMetadataManager._primaryKeyToRecordLocationMap;

    // Add record to update largestSeenTimestamp, largest seen timestamp: 80
    ThreadSafeMutableRoaringBitmap validDocIds0 = new ThreadSafeMutableRoaringBitmap();
    MutableSegment segment0 = mockMutableSegment(1, validDocIds0, null);
    upsertMetadataManager.addRecord(segment0, new RecordInfo(makePrimaryKey(10), 1, new Double(80), false));
    checkRecordLocationForTTL(recordLocationMap, 10, segment0, 1, 80, HashFunction.NONE);

    // Add a segment with segmentEndTime = 80, so it will not be skipped
    int numRecords = 4;
    int[] primaryKeys = new int[]{0, 1, 2, 3};
    Number[] timestamps = new Number[]{100, 100, 120, 80};
    ThreadSafeMutableRoaringBitmap validDocIds1 = new ThreadSafeMutableRoaringBitmap();
    List<PrimaryKey> primaryKeys1 = getPrimaryKeyList(numRecords, primaryKeys);
    ImmutableSegmentImpl segment1 =
        mockImmutableSegmentWithEndTime(1, validDocIds1, null, primaryKeys1, COMPARISON_COLUMNS, new Double(80), null);

    // load segment1 with segmentEndTime: 80, largest seen timestamp: 80. the segment will be loaded.
    upsertMetadataManager.addSegment(segment1, validDocIds1, null,
        getRecordInfoListForTTL(numRecords, primaryKeys, timestamps).iterator());
    assertEquals(recordLocationMap.size(), 5);
    checkRecordLocationForTTL(recordLocationMap, 0, segment1, 0, 100, HashFunction.NONE);
    checkRecordLocationForTTL(recordLocationMap, 1, segment1, 1, 100, HashFunction.NONE);
    checkRecordLocationForTTL(recordLocationMap, 2, segment1, 2, 120, HashFunction.NONE);
    checkRecordLocationForTTL(recordLocationMap, 3, segment1, 3, 80, HashFunction.NONE);
    checkRecordLocationForTTL(recordLocationMap, 10, segment0, 1, 80, HashFunction.NONE);
    assertEquals(validDocIds1.getMutableRoaringBitmap().toArray(), new int[]{0, 1, 2, 3});
    assertEquals(upsertMetadataManager.getWatermark(), 80);

    // Add record to update largestSeenTimestamp, largest seen timestamp: 120
    upsertMetadataManager.addRecord(segment0, new RecordInfo(makePrimaryKey(0), 0, new Double(120), false));
    assertEquals(validDocIds1.getMutableRoaringBitmap().toArray(), new int[]{1, 2, 3});
    assertEquals(recordLocationMap.size(), 5);
    checkRecordLocationForTTL(recordLocationMap, 0, segment0, 0, 120, HashFunction.NONE);
    checkRecordLocationForTTL(recordLocationMap, 1, segment1, 1, 100, HashFunction.NONE);
    checkRecordLocationForTTL(recordLocationMap, 2, segment1, 2, 120, HashFunction.NONE);
    checkRecordLocationForTTL(recordLocationMap, 3, segment1, 3, 80, HashFunction.NONE);
    checkRecordLocationForTTL(recordLocationMap, 10, segment0, 1, 80, HashFunction.NONE);
    assertEquals(upsertMetadataManager.getWatermark(), 120);

    // Add an out-of-ttl segment, verify all the invalid docs should not show up again.
    // Add a segment with segmentEndTime: 80, largest seen timestamp: 120. the segment will be skipped.
    List<PrimaryKey> primaryKeys2 = getPrimaryKeyList(numRecords, new int[]{100, 101, 102, 103});
    int[] docIds2 = new int[]{0, 1};
    MutableRoaringBitmap validDocIdsSnapshot2 = new MutableRoaringBitmap();
    validDocIdsSnapshot2.add(docIds2);
    ThreadSafeMutableRoaringBitmap validDocIds2 = new ThreadSafeMutableRoaringBitmap();
    ImmutableSegmentImpl segment2 =
        mockImmutableSegmentWithEndTime(1, validDocIds2, null, primaryKeys2, COMPARISON_COLUMNS, new Double(80),
            validDocIdsSnapshot2);
    upsertMetadataManager.addSegment(segment2);
    // out of ttl segment should not be added to recordLocationMap
    assertEquals(recordLocationMap.size(), 5);
    checkRecordLocationForTTL(recordLocationMap, 0, segment0, 0, 120, HashFunction.NONE);
    checkRecordLocationForTTL(recordLocationMap, 1, segment1, 1, 100, HashFunction.NONE);
    checkRecordLocationForTTL(recordLocationMap, 2, segment1, 2, 120, HashFunction.NONE);
    checkRecordLocationForTTL(recordLocationMap, 3, segment1, 3, 80, HashFunction.NONE);
    checkRecordLocationForTTL(recordLocationMap, 10, segment0, 1, 80, HashFunction.NONE);
    assertEquals(upsertMetadataManager.getWatermark(), 120);

    // Stop the metadata manager
    upsertMetadataManager.stop();

    // Close the metadata manager
    upsertMetadataManager.close();
  }

  private void verifyAddSegmentOutOfTTLWithRecordDelete()
      throws IOException {
    ConcurrentMapPartitionUpsertMetadataManager upsertMetadataManager =
        new ConcurrentMapPartitionUpsertMetadataManager(REALTIME_TABLE_NAME, 0, _contextBuilder.build());
    Map<Object, RecordLocation> recordLocationMap = upsertMetadataManager._primaryKeyToRecordLocationMap;
    Set<IndexSegment> trackedSegments = upsertMetadataManager._trackedSegments;

    // Add the first segment, it will not be skipped
    int numRecords = 6;
    int[] primaryKeys = new int[]{0, 1, 2, 0, 1, 0};
    int[] timestamps = new int[]{100, 100, 100, 80, 120, 100};
    boolean[] deleteFlags = new boolean[]{false, false, false, true, true, false};
    ThreadSafeMutableRoaringBitmap validDocIds1 = new ThreadSafeMutableRoaringBitmap();
    ThreadSafeMutableRoaringBitmap queryableDocIds1 = new ThreadSafeMutableRoaringBitmap();
    List<PrimaryKey> primaryKeys1 = getPrimaryKeyList(numRecords, primaryKeys);

    int[] docIds1 = new int[]{2, 4, 5};
    MutableRoaringBitmap validDocIdsSnapshot1 = new MutableRoaringBitmap();
    validDocIdsSnapshot1.add(docIds1);
    ImmutableSegmentImpl segment1 =
        mockImmutableSegmentWithEndTime(1, validDocIds1, queryableDocIds1, primaryKeys1, COMPARISON_COLUMNS,
            new Double(120), validDocIdsSnapshot1);

    // get recordInfo from validDocIdSnapshot.
    // segment1 snapshot: 0 -> {5, 100}, 1 -> {4, 120}, 2 -> {2, 100}
    List<RecordInfo> recordInfoList1;
    recordInfoList1 = getRecordInfoList(validDocIdsSnapshot1, primaryKeys, timestamps, deleteFlags);

    upsertMetadataManager.addSegment(segment1, validDocIds1, queryableDocIds1, recordInfoList1.iterator());
    trackedSegments.add(segment1);
    // segment1: 0 -> {5, 100}, 1 -> {4, 120}, 2 -> {2, 100}
    assertEquals(recordLocationMap.size(), 3);
    checkRecordLocation(recordLocationMap, 0, segment1, 5, 100, HashFunction.NONE);
    checkRecordLocation(recordLocationMap, 1, segment1, 4, 120, HashFunction.NONE);
    checkRecordLocation(recordLocationMap, 2, segment1, 2, 100, HashFunction.NONE);
    assertEquals(validDocIds1.getMutableRoaringBitmap().toArray(), new int[]{2, 4, 5});
    assertEquals(queryableDocIds1.getMutableRoaringBitmap().toArray(), new int[]{2, 5});

    // Add the second segment, it will be skipped.
    numRecords = 5;
    primaryKeys = new int[]{0, 1, 2, 3, 4};
    timestamps = new int[]{40, 40, 40, 40, 40};
    deleteFlags = new boolean[]{false, false, true, false, true};
    ThreadSafeMutableRoaringBitmap validDocIds2 = new ThreadSafeMutableRoaringBitmap();
    ThreadSafeMutableRoaringBitmap queryableDocIds2 = new ThreadSafeMutableRoaringBitmap();
    MutableRoaringBitmap validDocIdsSnapshot2 = new MutableRoaringBitmap();

    int[] docIds2 = new int[]{3, 4};
    validDocIdsSnapshot2.add(docIds2);
    ImmutableSegmentImpl segment2 =
        mockImmutableSegmentWithEndTime(2, validDocIds2, queryableDocIds2, getPrimaryKeyList(numRecords, primaryKeys),
            COMPARISON_COLUMNS, new Double(40), validDocIdsSnapshot2);

    // get recordInfo from validDocIdSnapshot.
    // segment2 snapshot: 3 -> {3, 40}, 4 -> {4, 40}
    // segment1 snapshot: 0 -> {5, 100}, 1 -> {4, 120}, 2 -> {2, 100}
    List<RecordInfo> recordInfoList2;
    recordInfoList2 = getRecordInfoList(validDocIdsSnapshot2, primaryKeys, timestamps, deleteFlags);

    upsertMetadataManager.addSegment(segment2, validDocIds2, queryableDocIds2, recordInfoList2.iterator());
    trackedSegments.add(segment2);

    // segment1: 0 -> {5, 100}, 1 -> {4, 120}, 2 -> {2, 100}
    // segment2: 3 -> {3, 40}, 4 -> {4, 40}
    assertEquals(recordLocationMap.size(), 5);
    checkRecordLocation(recordLocationMap, 0, segment1, 5, 100, HashFunction.NONE);
    checkRecordLocation(recordLocationMap, 1, segment1, 4, 120, HashFunction.NONE);
    checkRecordLocation(recordLocationMap, 2, segment1, 2, 100, HashFunction.NONE);
    checkRecordLocation(recordLocationMap, 3, segment2, 3, 40, HashFunction.NONE);
    checkRecordLocation(recordLocationMap, 4, segment2, 4, 40, HashFunction.NONE);
    assertEquals(validDocIds1.getMutableRoaringBitmap().toArray(), new int[]{2, 4, 5});
    assertEquals(validDocIds2.getMutableRoaringBitmap().toArray(), new int[]{3, 4});
    assertEquals(queryableDocIds1.getMutableRoaringBitmap().toArray(), new int[]{2, 5});
    assertEquals(queryableDocIds2.getMutableRoaringBitmap().toArray(), new int[]{3});

    // Stop the metadata manager
    upsertMetadataManager.stop();

    // Close the metadata manager
    upsertMetadataManager.close();
  }

  public void verifyGetQueryableDocIds(boolean isDeleteColumnNull, boolean[] deleteFlags,
      MutableRoaringBitmap validDocIdsSnapshot, MutableRoaringBitmap queryableDocIds) {
    ConcurrentMapPartitionUpsertMetadataManager upsertMetadataManager =
        new ConcurrentMapPartitionUpsertMetadataManager(REALTIME_TABLE_NAME, 0, _contextBuilder.build());

    try (MockedConstruction<PinotSegmentColumnReader> deleteColReader = mockConstruction(PinotSegmentColumnReader.class,
        (mockReader, context) -> {
          for (int i = 0; i < deleteFlags.length; i++) {
            when(mockReader.isNull(i)).thenReturn(isDeleteColumnNull);
            when(mockReader.getValue(i)).thenReturn(deleteFlags[i]);
          }
        })) {

      SegmentMetadataImpl segmentMetadata = mock(SegmentMetadataImpl.class);
      ColumnMetadata columnMetadata = mock(ColumnMetadata.class);
      when(segmentMetadata.getTotalDocs()).thenReturn(deleteFlags.length);
      when(segmentMetadata.getColumnMetadataMap()).thenReturn(new TreeMap() {{
        this.put(COMPARISON_COLUMNS.get(0), columnMetadata);
      }});

      ImmutableSegmentImpl segment =
          mockImmutableSegmentWithSegmentMetadata(1, new ThreadSafeMutableRoaringBitmap(), null, null, segmentMetadata,
              validDocIdsSnapshot);
      assertEquals(upsertMetadataManager.getQueryableDocIds(segment, validDocIdsSnapshot), queryableDocIds);
    }
  }

  private void verifyAddSegmentOutOfTTL(Comparable comparisonValue)
      throws IOException {
    ConcurrentMapPartitionUpsertMetadataManager upsertMetadataManager =
        new ConcurrentMapPartitionUpsertMetadataManager(REALTIME_TABLE_NAME, 0, _contextBuilder.build());
    Map<Object, ConcurrentMapPartitionUpsertMetadataManager.RecordLocation> recordLocationMap =
        upsertMetadataManager._primaryKeyToRecordLocationMap;

    // Add record to update largestSeenTimestamp, largest seen timestamp: comparisonValue
    ThreadSafeMutableRoaringBitmap validDocIds0 = new ThreadSafeMutableRoaringBitmap();
    MutableSegment segment0 = mockMutableSegment(1, validDocIds0, null);
    upsertMetadataManager.addRecord(segment0, new RecordInfo(makePrimaryKey(10), 1, comparisonValue, false));
    checkRecordLocationForTTL(recordLocationMap, 10, segment0, 1, 80, HashFunction.NONE);
    assertEquals(upsertMetadataManager.getWatermark(), 80);

    // add a segment with segmentEndTime = -1 so it will be skipped since it out-of-TTL
    int numRecords = 4;
    int[] primaryKeys = new int[]{0, 1, 2, 3};
    ThreadSafeMutableRoaringBitmap validDocIds1 = new ThreadSafeMutableRoaringBitmap();
    List<PrimaryKey> primaryKeys1 = getPrimaryKeyList(numRecords, primaryKeys);
    ImmutableSegmentImpl segment1 =
        mockImmutableSegmentWithEndTime(1, validDocIds1, null, primaryKeys1, COMPARISON_COLUMNS, -1, null);

    // load segment1.
    upsertMetadataManager.addSegment(segment1);
    assertEquals(recordLocationMap.size(), 1);
    checkRecordLocationForTTL(recordLocationMap, 10, segment0, 1, 80, HashFunction.NONE);
    assertEquals(upsertMetadataManager.getWatermark(), 80);

    // Stop the metadata manager
    upsertMetadataManager.stop();

    // Close the metadata manager
    upsertMetadataManager.close();
  }

  // Add the following utils function since the Comparison column is a long value for TTL enabled upsert table.
  private List<RecordInfo> getRecordInfoListForTTL(int numRecords, int[] primaryKeys, Number[] timestamps) {
    List<RecordInfo> recordInfoList = new ArrayList<>();
    for (int i = 0; i < numRecords; i++) {
      recordInfoList.add(
          new RecordInfo(makePrimaryKey(primaryKeys[i]), i, new Double(timestamps[i].doubleValue()), false));
    }
    return recordInfoList;
  }

  // Add the following utils function since the Comparison column is a long value for TTL enabled upsert table.
  private static void checkRecordLocationForTTL(Map<Object, RecordLocation> recordLocationMap, int keyValue,
      IndexSegment segment, int docId, Number comparisonValue, HashFunction hashFunction) {
    RecordLocation recordLocation =
        recordLocationMap.get(HashUtils.hashPrimaryKey(makePrimaryKey(keyValue), hashFunction));
    assertNotNull(recordLocation);
    assertSame(recordLocation.getSegment(), segment);
    assertEquals(recordLocation.getDocId(), docId);
    assertEquals(((Number) recordLocation.getComparisonValue()).doubleValue(), comparisonValue.doubleValue());
  }

  @Test
  public void testHashPrimaryKey() {
    PrimaryKey pk = new PrimaryKey(new Object[]{"uuid-1", "uuid-2", "uuid-3"});
    assertEquals(BytesUtils.toHexString(((ByteArray) HashUtils.hashPrimaryKey(pk, HashFunction.MD5)).getBytes()),
        "6ca926be8c2d1d980acf48ba48418e24");
    assertEquals(BytesUtils.toHexString(((ByteArray) HashUtils.hashPrimaryKey(pk, HashFunction.MURMUR3)).getBytes()),
        "e4540494e43b27e312d01f33208c6a4e");
    // reorder
    pk = new PrimaryKey(new Object[]{"uuid-3", "uuid-2", "uuid-1"});
    assertEquals(BytesUtils.toHexString(((ByteArray) HashUtils.hashPrimaryKey(pk, HashFunction.MD5)).getBytes()),
        "fc2159b78d07f803fdfb0b727315a445");
    assertEquals(BytesUtils.toHexString(((ByteArray) HashUtils.hashPrimaryKey(pk, HashFunction.MURMUR3)).getBytes()),
        "37fab5ef0ea39711feabcdc623cb8a4e");
  }

  /**
   * Use a wrapper class to ensure different value has different reference.
   */
  private static class IntWrapper implements Comparable<IntWrapper> {
    final int _value;

    IntWrapper(int value) {
      _value = value;
    }

    @Override
    public int compareTo(IntWrapper o) {
      return Integer.compare(_value, o._value);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof IntWrapper)) {
        return false;
      }
      IntWrapper that = (IntWrapper) o;
      return _value == that._value;
    }

    @Override
    public int hashCode() {
      return _value;
    }
  }
}
