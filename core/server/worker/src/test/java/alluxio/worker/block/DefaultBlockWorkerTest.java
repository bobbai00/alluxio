/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.worker.block;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import alluxio.AlluxioTestDirectory;
import alluxio.AlluxioURI;
import alluxio.ConfigurationRule;
import alluxio.Constants;
import alluxio.Sessions;
import alluxio.conf.Configuration;
import alluxio.conf.PropertyKey;
import alluxio.exception.WorkerOutOfSpaceException;
import alluxio.exception.status.DeadlineExceededException;
import alluxio.grpc.Block;
import alluxio.grpc.BlockStatus;
import alluxio.proto.dataserver.Protocol;
import alluxio.underfs.UfsManager;
import alluxio.underfs.UnderFileSystem;
import alluxio.underfs.UnderFileSystemConfiguration;
import alluxio.util.IdUtils;
import alluxio.util.io.BufferUtils;
import alluxio.worker.block.io.BlockReader;
import alluxio.worker.block.io.BlockWriter;
import alluxio.worker.block.meta.TempBlockMeta;
import alluxio.worker.file.FileSystemMasterClient;

import com.google.common.collect.ImmutableMap;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Unit tests for {@link DefaultBlockWorker}.
 */
public class DefaultBlockWorkerTest {
  private static final int BLOCK_SIZE = 128;
  private TieredBlockStore mTieredBlockStore;
  private DefaultBlockWorker mBlockWorker;
  private FileSystemMasterClient mFileSystemMasterClient;
  private Random mRandom;
  private final String mMemDir =
      AlluxioTestDirectory.createTemporaryDirectory(Constants.MEDIUM_MEM).getAbsolutePath();
  private final String mHddDir =
      AlluxioTestDirectory.createTemporaryDirectory(Constants.MEDIUM_HDD).getAbsolutePath();
  private String mRootUfs;
  private String mTestFilePath;

  @Rule
  public TemporaryFolder mTestFolder = new TemporaryFolder();
  @Rule
  public ConfigurationRule mConfigurationRule =
      new ConfigurationRule(new ImmutableMap.Builder<PropertyKey, Object>()
          .put(PropertyKey.WORKER_TIERED_STORE_LEVELS, 2)
          .put(PropertyKey.WORKER_TIERED_STORE_LEVEL0_ALIAS, Constants.MEDIUM_MEM)
          .put(PropertyKey.WORKER_TIERED_STORE_LEVEL0_DIRS_MEDIUMTYPE, Constants.MEDIUM_MEM)
          .put(PropertyKey.WORKER_TIERED_STORE_LEVEL0_DIRS_QUOTA, "1GB")
          .put(PropertyKey.WORKER_TIERED_STORE_LEVEL0_DIRS_PATH, mMemDir)
          .put(PropertyKey.WORKER_TIERED_STORE_LEVEL1_ALIAS, Constants.MEDIUM_HDD)
          .put(PropertyKey.WORKER_TIERED_STORE_LEVEL1_DIRS_MEDIUMTYPE, Constants.MEDIUM_HDD)
          .put(PropertyKey.WORKER_TIERED_STORE_LEVEL1_DIRS_QUOTA, "2GB")
          .put(PropertyKey.WORKER_TIERED_STORE_LEVEL1_DIRS_PATH, mHddDir)
          .put(PropertyKey.WORKER_RPC_PORT, 0)
          .put(PropertyKey.WORKER_MANAGEMENT_TIER_ALIGN_RESERVED_BYTES, "0")
          .put(PropertyKey.MASTER_MOUNT_TABLE_ROOT_UFS, AlluxioTestDirectory
              .createTemporaryDirectory("DefaultBlockWorkerTest").getAbsolutePath())
          .build(), Configuration.modifiableGlobal());
  private BlockStore mBlockStore;

  /**
   * Sets up all dependencies before a test runs.
   */
  @Before
  public void before() throws IOException {
    mRandom = new Random();
    BlockMasterClient blockMasterClient = mock(BlockMasterClient.class);
    BlockMasterClientPool blockMasterClientPool = spy(new BlockMasterClientPool());
    when(blockMasterClientPool.createNewResource()).thenReturn(blockMasterClient);
    mTieredBlockStore = spy(new TieredBlockStore());
    UfsManager ufsManager = mock(UfsManager.class);
    AtomicReference<Long> workerId = new AtomicReference<>(-1L);
    mBlockStore =
        new MonoBlockStore(mTieredBlockStore, blockMasterClientPool, ufsManager, workerId);
    mFileSystemMasterClient = mock(FileSystemMasterClient.class);
    Sessions sessions = mock(Sessions.class);
    mRootUfs = Configuration.getString(PropertyKey.MASTER_MOUNT_TABLE_ROOT_UFS);
    UfsManager.UfsClient ufsClient = new UfsManager.UfsClient(
        () -> UnderFileSystem.Factory.create(mRootUfs,
            UnderFileSystemConfiguration.defaults(Configuration.global())),
        new AlluxioURI(mRootUfs));
    when(ufsManager.get(anyLong())).thenReturn(ufsClient);
    mBlockWorker = new DefaultBlockWorker(blockMasterClientPool, mFileSystemMasterClient, sessions,
        mBlockStore, workerId);
    // Write an actual file to UFS
    mTestFilePath = File.createTempFile("temp", null, new File(mRootUfs)).getAbsolutePath();
    byte[] buffer = BufferUtils.getIncreasingByteArray((int) (BLOCK_SIZE * 1.5));
    BufferUtils.writeBufferToFile(mTestFilePath, buffer);
  }

  @Test
  public void abortBlock() throws Exception {
    long blockId = mRandom.nextLong();
    long sessionId = mRandom.nextLong();
    mBlockWorker.createBlock(sessionId, blockId, 0,
        new CreateBlockOptions(null, Constants.MEDIUM_MEM, 1));
    mBlockWorker.abortBlock(sessionId, blockId);
    assertFalse(mBlockWorker.getBlockStore().getTempBlockMeta(blockId).isPresent());
  }

  @Test
  public void commitBlock() throws Exception {
    long blockId = mRandom.nextLong();
    long sessionId = mRandom.nextLong();
    mBlockWorker.createBlock(sessionId, blockId, 0,
        new CreateBlockOptions(null, Constants.MEDIUM_MEM, 1));
    assertFalse(mBlockWorker.getBlockStore().hasBlockMeta(blockId));
    mBlockWorker.commitBlock(sessionId, blockId, true);
    assertTrue(mBlockWorker.getBlockStore().hasBlockMeta(blockId));
  }

  @Test
  public void commitBlockOnRetry() throws Exception {
    long blockId = mRandom.nextLong();
    long sessionId = mRandom.nextLong();
    mBlockWorker.createBlock(sessionId, blockId, 0,
        new CreateBlockOptions(null, Constants.MEDIUM_MEM, 1));
    mBlockWorker.commitBlock(sessionId, blockId, true);
    mBlockWorker.commitBlock(sessionId, blockId, true);
    assertTrue(mBlockWorker.getBlockStore().hasBlockMeta(blockId));
  }

  @Test
  public void createBlock() throws Exception {
    long blockId = mRandom.nextLong();
    long sessionId = mRandom.nextLong();
    long initialBytes = 1;
    String path = mBlockWorker.createBlock(sessionId, blockId, 0,
        new CreateBlockOptions(null, null, initialBytes));
    assertTrue(path.startsWith(mMemDir)); // tier 0 is mem
  }

  @Test
  public void createBlockLowerTier() throws Exception {
    long blockId = mRandom.nextLong();
    long sessionId = mRandom.nextLong();
    long initialBytes = 1;
    String path = mBlockWorker.createBlock(sessionId, blockId, 1,
        new CreateBlockOptions(null, null, initialBytes));
    assertTrue(path.startsWith(mHddDir));
  }

  @Test
  public void getTempBlockWriter() throws Exception {
    long blockId = mRandom.nextLong();
    long sessionId = mRandom.nextLong();
    mBlockWorker.createBlock(sessionId, blockId, 0,
        new CreateBlockOptions(null, Constants.MEDIUM_MEM, 1));
    try (BlockWriter blockWriter = mBlockWorker.createBlockWriter(sessionId, blockId)) {
      blockWriter.append(BufferUtils.getIncreasingByteBuffer(10));
      TempBlockMeta meta = mBlockWorker.getBlockStore().getTempBlockMeta(blockId).get();
      assertEquals(Constants.MEDIUM_MEM, meta.getBlockLocation().mediumType());
    }
    mBlockWorker.abortBlock(sessionId, blockId);
  }

  @Test
  public void getReport() {
    BlockHeartbeatReport report = mBlockWorker.getReport();
    assertEquals(0, report.getAddedBlocks().size());
    assertEquals(0, report.getRemovedBlocks().size());
  }

  @Test
  public void getStoreMeta() throws Exception {
    long blockId1 = mRandom.nextLong();
    long blockId2 = mRandom.nextLong();
    long sessionId = mRandom.nextLong();
    mBlockWorker.createBlock(sessionId, blockId1, 0, new CreateBlockOptions(null, "", 1L));
    mBlockWorker.createBlock(sessionId, blockId2, 1, new CreateBlockOptions(null, "", 1L));

    BlockStoreMeta storeMeta = mBlockWorker.getStoreMetaFull();
    assertEquals(2, storeMeta.getBlockList().size());
    assertEquals(2, storeMeta.getBlockListByStorageLocation().size());
    assertEquals(0, storeMeta.getBlockList().get("MEM").size());
    assertEquals(3L * Constants.GB, storeMeta.getCapacityBytes());
    assertEquals(2L, storeMeta.getUsedBytes());
    assertEquals(1L, storeMeta.getUsedBytesOnTiers().get("MEM").longValue());
    assertEquals(1L, storeMeta.getUsedBytesOnTiers().get("HDD").longValue());

    BlockStoreMeta storeMeta2 = mBlockWorker.getStoreMeta();
    assertEquals(3L * Constants.GB, storeMeta2.getCapacityBytes());
    assertEquals(2L, storeMeta2.getUsedBytes());

    mBlockWorker.commitBlock(sessionId, blockId1, true);
    mBlockWorker.commitBlock(sessionId, blockId2, true);

    storeMeta = mBlockWorker.getStoreMetaFull();
    assertEquals(1, storeMeta.getBlockList().get("MEM").size());
    assertEquals(1, storeMeta.getBlockList().get("HDD").size());
    Map<BlockStoreLocation, List<Long>> blockLocations = storeMeta.getBlockListByStorageLocation();
    assertEquals(1, blockLocations.get(
            new BlockStoreLocation("MEM", 0, "MEM")).size());
    assertEquals(1, blockLocations.get(
            new BlockStoreLocation("HDD", 0, "HDD")).size());
    assertEquals(2, storeMeta.getNumberOfBlocks());
  }

  @Test
  public void hasBlockMeta() throws Exception  {
    long sessionId = mRandom.nextLong();
    long blockId = mRandom.nextLong();
    assertFalse(mBlockWorker.getBlockStore().hasBlockMeta(blockId));
    mBlockWorker.createBlock(sessionId, blockId, 0,
        new CreateBlockOptions(null, Constants.MEDIUM_MEM, 1));
    mBlockWorker.commitBlock(sessionId, blockId, true);
    assertTrue(mBlockWorker.getBlockStore().hasBlockMeta(blockId));
  }

  @Test
  public void removeBlock() throws Exception {
    long blockId = mRandom.nextLong();
    long sessionId = mRandom.nextLong();
    mBlockWorker.createBlock(sessionId, blockId, 1, new CreateBlockOptions(null, "", 1));
    mBlockWorker.commitBlock(sessionId, blockId, true);
    mBlockWorker.removeBlock(sessionId, blockId);
    assertFalse(mBlockWorker.getBlockStore().hasBlockMeta(blockId));
  }

  @Test
  public void requestSpace() throws Exception {
    long blockId = mRandom.nextLong();
    long sessionId = mRandom.nextLong();
    long initialBytes = 512;
    long additionalBytes = 1024;
    mBlockWorker.createBlock(sessionId, blockId, 1, new CreateBlockOptions(null, "", initialBytes));
    mBlockWorker.requestSpace(sessionId, blockId, additionalBytes);
    assertEquals(initialBytes + additionalBytes,
        mBlockWorker.getBlockStore().getTempBlockMeta(blockId).get().getBlockSize());
  }

  @Test
  public void requestSpaceNoBlock() {
    long blockId = mRandom.nextLong();
    long sessionId = mRandom.nextLong();
    long additionalBytes = 1;
    assertThrows(IllegalStateException.class,
        () -> mBlockWorker.requestSpace(sessionId, blockId, additionalBytes)
    );
  }

  @Test
  public void requestSpaceNoSpace() throws Exception {
    long blockId = mRandom.nextLong();
    long sessionId = mRandom.nextLong();
    long additionalBytes = 2L * Constants.GB + 1;
    mBlockWorker.createBlock(sessionId, blockId, 1, new CreateBlockOptions(null, "", 1));
    assertThrows(WorkerOutOfSpaceException.class,
        () -> mBlockWorker.requestSpace(sessionId, blockId, additionalBytes)
    );
  }

  @Test
  public void updatePinList() {
    Set<Long> pinnedInodes = new HashSet<>();
    pinnedInodes.add(mRandom.nextLong());

    mBlockWorker.updatePinList(pinnedInodes);
    verify(mTieredBlockStore).updatePinnedInodes(pinnedInodes);
  }

  @Test
  public void getFileInfo() throws Exception {
    long fileId = mRandom.nextLong();
    mBlockWorker.getFileInfo(fileId);
    verify(mFileSystemMasterClient).getFileInfo(fileId);
  }

  @Test
  public void getBlockReader() throws Exception {
    long blockId = mRandom.nextLong();
    long sessionId = mRandom.nextLong();
    mBlockWorker.createBlock(sessionId, blockId, 0,
        new CreateBlockOptions(null, Constants.MEDIUM_MEM, 1));
    mBlockWorker.commitBlock(sessionId, blockId, true);
    BlockReader reader = mBlockWorker.createBlockReader(IdUtils.createSessionId(), blockId, 0,
        false, Protocol.OpenUfsBlockOptions.newBuilder().build());
    // reader will hold the lock
    assertThrows(DeadlineExceededException.class,
        () -> mTieredBlockStore.removeBlockInternal(sessionId, blockId, 10)
    );
    reader.close();
    mTieredBlockStore.removeBlockInternal(sessionId, blockId, 10);
  }

  @Test
  public void loadMultipleFromUfs() throws IOException {
    Block block =
        Block.newBuilder().setBlockId(0).setBlockSize(BLOCK_SIZE).setMountId(0).setOffsetInFile(0)
            .setUfsPath(mTestFilePath).build();
    Block block2 = Block.newBuilder().setBlockId(1).setBlockSize(BLOCK_SIZE / 2).setMountId(0)
        .setOffsetInFile(BLOCK_SIZE).setUfsPath(mTestFilePath).build();

    List<BlockStatus> res =
        mBlockWorker.load(Arrays.asList(block, block2), "test", OptionalInt.empty());
    assertEquals(res.size(), 0);
    assertTrue(mBlockStore.hasBlockMeta(0));
    assertTrue(mBlockStore.hasBlockMeta(1));
    BlockReader reader = mBlockWorker.createBlockReader(0, 0, 0, false,
        Protocol.OpenUfsBlockOptions.getDefaultInstance());
    // Read entire block by setting the length to be block size.
    ByteBuffer buffer = reader.read(0, BLOCK_SIZE);
    assertTrue(BufferUtils.equalIncreasingByteBuffer(0, BLOCK_SIZE, buffer));
    reader = mBlockWorker.createBlockReader(0, 1, 0, false,
        Protocol.OpenUfsBlockOptions.getDefaultInstance());
    buffer = reader.read(0, BLOCK_SIZE / 2);
    assertTrue(BufferUtils.equalIncreasingByteBuffer(BLOCK_SIZE, BLOCK_SIZE / 2, buffer));
  }

  @Test
  public void loadDuplicateBlock() {
    int blockId = 0;
    Block blocks = Block.newBuilder().setBlockId(blockId).setBlockSize(BLOCK_SIZE).setMountId(0)
        .setOffsetInFile(0).setUfsPath(mTestFilePath).build();
    List<BlockStatus> res =
        mBlockWorker.load(Collections.singletonList(blocks), "test", OptionalInt.empty());
    assertEquals(res.size(), 0);
    List<BlockStatus> failure =
        mBlockWorker.load(Collections.singletonList(blocks), "test", OptionalInt.empty());
    assertEquals(failure.size(), 1);
  }
}
