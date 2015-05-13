/**
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
package org.apache.hadoop.hdfs;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.BlockLocation;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.protocol.HdfsConstants;
import org.apache.hadoop.hdfs.server.datanode.DataNode;
import org.apache.hadoop.io.erasurecode.rawcoder.RSRawDecoder;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;

public class TestWriteReadStripedFile {
  private static int dataBlocks = HdfsConstants.NUM_DATA_BLOCKS;
  private static int parityBlocks = HdfsConstants.NUM_PARITY_BLOCKS;


  private static DistributedFileSystem fs;
  private final static int cellSize = HdfsConstants.BLOCK_STRIPED_CELL_SIZE;
  private final static int stripesPerBlock = 4;
  static int blockSize = cellSize * stripesPerBlock;
  static int numDNs = dataBlocks + parityBlocks + 2;

  private static MiniDFSCluster cluster;

  @BeforeClass
  public static void setup() throws IOException {
    Configuration conf = new Configuration();
    conf.setLong(DFSConfigKeys.DFS_BLOCK_SIZE_KEY, blockSize);
    cluster = new MiniDFSCluster.Builder(conf).numDataNodes(numDNs).build();
    cluster.getFileSystem().getClient().createErasureCodingZone("/", null);
    fs = cluster.getFileSystem();
  }

  @AfterClass
  public static void tearDown() {
    if (cluster != null) {
      cluster.shutdown();
    }
  }

  @Test
  public void testFileEmpty() throws IOException {
    testOneFileUsingDFSStripedInputStream("/EmptyFile", 0);
  }

  @Test
  public void testFileSmallerThanOneCell1() throws IOException {
    testOneFileUsingDFSStripedInputStream("/SmallerThanOneCell", 1);
  }

  @Test
  public void testFileSmallerThanOneCell2() throws IOException {
    testOneFileUsingDFSStripedInputStream("/SmallerThanOneCell", cellSize - 1);
  }

  @Test
  public void testFileEqualsWithOneCell() throws IOException {
    testOneFileUsingDFSStripedInputStream("/EqualsWithOneCell", cellSize);
  }

  @Test
  public void testFileSmallerThanOneStripe1() throws IOException {
    testOneFileUsingDFSStripedInputStream("/SmallerThanOneStripe",
        cellSize * dataBlocks - 1);
  }

  @Test
  public void testFileSmallerThanOneStripe2() throws IOException {
    testOneFileUsingDFSStripedInputStream("/SmallerThanOneStripe",
        cellSize + 123);
  }

  @Test
  public void testFileEqualsWithOneStripe() throws IOException {
    testOneFileUsingDFSStripedInputStream("/EqualsWithOneStripe",
        cellSize * dataBlocks);
  }

  @Test
  public void testFileMoreThanOneStripe1() throws IOException {
    testOneFileUsingDFSStripedInputStream("/MoreThanOneStripe1",
        cellSize * dataBlocks + 123);
  }

  @Test
  public void testFileMoreThanOneStripe2() throws IOException {
    testOneFileUsingDFSStripedInputStream("/MoreThanOneStripe2",
        cellSize * dataBlocks + cellSize * dataBlocks + 123);
  }

  @Test
  public void testLessThanFullBlockGroup() throws IOException {
    testOneFileUsingDFSStripedInputStream("/LessThanFullBlockGroup",
        cellSize * dataBlocks * (stripesPerBlock - 1) + cellSize);
  }

  @Test
  public void testFileFullBlockGroup() throws IOException {
    testOneFileUsingDFSStripedInputStream("/FullBlockGroup",
        blockSize * dataBlocks);
  }

  @Test
  public void testFileMoreThanABlockGroup1() throws IOException {
    testOneFileUsingDFSStripedInputStream("/MoreThanABlockGroup1",
        blockSize * dataBlocks + 123);
  }

  @Test
  public void testFileMoreThanABlockGroup2() throws IOException {
    testOneFileUsingDFSStripedInputStream("/MoreThanABlockGroup2",
        blockSize * dataBlocks + cellSize+ 123);
  }


  @Test
  public void testFileMoreThanABlockGroup3() throws IOException {
    testOneFileUsingDFSStripedInputStream("/MoreThanABlockGroup3",
        blockSize * dataBlocks * 3 + cellSize * dataBlocks
            + cellSize + 123);
  }

  private byte[] generateBytes(int cnt) {
    byte[] bytes = new byte[cnt];
    for (int i = 0; i < cnt; i++) {
      bytes[i] = getByte(i);
    }
    return bytes;
  }

  private int readAll(FSDataInputStream in, byte[] buf) throws IOException {
    int readLen = 0;
    int ret;
    do {
      ret = in.read(buf, readLen, buf.length - readLen);
      if (ret > 0) {
        readLen += ret;
      }
    } while (ret >= 0 && readLen < buf.length);
    return readLen;
  }

  private byte getByte(long pos) {
    final int mod = 29;
    return (byte) (pos % mod + 1);
  }

  private void assertSeekAndRead(FSDataInputStream fsdis, int pos,
      int writeBytes) throws IOException {
    fsdis.seek(pos);
    byte[] buf = new byte[writeBytes];
    int readLen = readAll(fsdis, buf);
    Assert.assertEquals(readLen, writeBytes - pos);
    for (int i = 0; i < readLen; i++) {
      Assert.assertEquals("Byte at " + i + " should be the same",
          getByte(pos + i), buf[i]);
    }
  }

  private void testOneFileUsingDFSStripedInputStream(String src, int writeBytes)
      throws IOException {
    Path testPath = new Path(src);
    final byte[] bytes = generateBytes(writeBytes);
    DFSTestUtil.writeFile(fs, testPath, new String(bytes));

    //check file length
    FileStatus status = fs.getFileStatus(testPath);
    long fileLength = status.getLen();
    Assert.assertEquals("File length should be the same",
        writeBytes, fileLength);

    // pread
    try (FSDataInputStream fsdis = fs.open(new Path(src))) {
      byte[] buf = new byte[writeBytes + 100];
      int readLen = fsdis.read(0, buf, 0, buf.length);
      readLen = readLen >= 0 ? readLen : 0;
      Assert.assertEquals("The length of file should be the same to write size",
          writeBytes, readLen);
      for (int i = 0; i < writeBytes; i++) {
        Assert.assertEquals("Byte at " + i + " should be the same", getByte(i),
            buf[i]);
      }
    }

    // stateful read with byte array
    try (FSDataInputStream fsdis = fs.open(new Path(src))) {
      byte[] buf = new byte[writeBytes + 100];
      int readLen = readAll(fsdis, buf);
      Assert.assertEquals("The length of file should be the same to write size",
          writeBytes, readLen);
      for (int i = 0; i < writeBytes; i++) {
        Assert.assertEquals("Byte at " + i + " should be the same", getByte(i),
            buf[i]);
      }
    }

    // seek and stateful read
    try (FSDataInputStream fsdis = fs.open(new Path(src))) {
      // seek to 1/2 of content
      int pos = writeBytes/2;
      assertSeekAndRead(fsdis, pos, writeBytes);

      // seek to 1/3 of content
      pos = writeBytes/3;
      assertSeekAndRead(fsdis, pos, writeBytes);

      // seek to 0 pos
      pos = 0;
      assertSeekAndRead(fsdis, pos, writeBytes);

      if (writeBytes > cellSize) {
        // seek to cellSize boundary
        pos = cellSize -1;
        assertSeekAndRead(fsdis, pos, writeBytes);
      }

      if (writeBytes > cellSize * dataBlocks) {
        // seek to striped cell group boundary
        pos = cellSize * dataBlocks - 1;
        assertSeekAndRead(fsdis, pos, writeBytes);
      }

      if (writeBytes > blockSize * dataBlocks) {
        // seek to striped block group boundary
        pos = blockSize * dataBlocks - 1;
        assertSeekAndRead(fsdis, pos, writeBytes);
      }

      try {
        fsdis.seek(-1);
        Assert.fail("Should be failed if seek to negative offset");
      } catch (EOFException e) {
        // expected
      }

      try {
        fsdis.seek(writeBytes + 1);
        Assert.fail("Should be failed if seek after EOF");
      } catch (EOFException e) {
        // expected
      }
    }

    // stateful read with ByteBuffer
    try (FSDataInputStream fsdis = fs.open(new Path(src))) {
      ByteBuffer buf = ByteBuffer.allocate(writeBytes + 100);
      int readLen = 0;
      int ret;
      do {
        ret = fsdis.read(buf);
        if (ret > 0) {
          readLen += ret;
        }
      } while (ret >= 0);
      readLen = readLen >= 0 ? readLen : 0;
      Assert.assertEquals("The length of file should be the same to write size",
          writeBytes, readLen);
      for (int i = 0; i < writeBytes; i++) {
        Assert.assertEquals("Byte at " + i + " should be the same", getByte(i),
            buf.array()[i]);
      }
    }

    // stateful read with 1KB size byte array
    try (FSDataInputStream fsdis = fs.open(new Path(src))) {
      final byte[] result = new byte[writeBytes];
      final byte[] buf = new byte[1024];
      int readLen = 0;
      int ret;
      do {
        ret = fsdis.read(buf, 0, buf.length);
        if (ret > 0) {
          System.arraycopy(buf, 0, result, readLen, ret);
          readLen += ret;
        }
      } while (ret >= 0);
      Assert.assertEquals("The length of file should be the same to write size",
          writeBytes, readLen);
      Assert.assertArrayEquals(bytes, result);
    }

    // stateful read using ByteBuffer with 1KB size
    try (FSDataInputStream fsdis = fs.open(new Path(src))) {
      final ByteBuffer result = ByteBuffer.allocate(writeBytes);
      final ByteBuffer buf = ByteBuffer.allocate(1024);
      int readLen = 0;
      int ret;
      do {
        ret = fsdis.read(buf);
        if (ret > 0) {
          readLen += ret;
          buf.flip();
          result.put(buf);
          buf.clear();
        }
      } while (ret >= 0);
      Assert.assertEquals("The length of file should be the same to write size",
          writeBytes, readLen);
      Assert.assertArrayEquals(bytes, result.array());
    }
  }

  @Test
  public void testWritePreadWithDNFailure() throws IOException {
    final int failedDNIdx = 2;
    final int length = cellSize * (dataBlocks + 2);
    Path testPath = new Path("/foo");
    final byte[] bytes = generateBytes(length);
    DFSTestUtil.writeFile(fs, testPath, new String(bytes));

    // shut down the DN that holds the last internal data block
    BlockLocation[] locs = fs.getFileBlockLocations(testPath, cellSize * 5,
        cellSize);
    String name = (locs[0].getNames())[failedDNIdx];
    for (DataNode dn : cluster.getDataNodes()) {
      int port = dn.getXferPort();
      if (name.contains(Integer.toString(port))) {
        dn.shutdown();
        break;
      }
    }

    // pread
    int startOffsetInFile = cellSize * 5;
    try (FSDataInputStream fsdis = fs.open(testPath)) {
      byte[] buf = new byte[length];
      int readLen = fsdis.read(startOffsetInFile, buf, 0, buf.length);
      Assert.assertEquals("The length of file should be the same to write size",
          length - startOffsetInFile, readLen);

      RSRawDecoder rsRawDecoder = new RSRawDecoder();
      rsRawDecoder.initialize(dataBlocks, parityBlocks, 1);
      byte[] expected = new byte[readLen];
      for (int i = startOffsetInFile; i < length; i++) {
        //TODO: workaround (filling fixed bytes), to remove after HADOOP-11938
        if ((i / cellSize) % dataBlocks == failedDNIdx) {
          expected[i - startOffsetInFile] = (byte)7;
        } else {
          expected[i - startOffsetInFile] = getByte(i);
        }
      }
      for (int i = startOffsetInFile; i < length; i++) {
        Assert.assertEquals("Byte at " + i + " should be the same",
            expected[i - startOffsetInFile], buf[i - startOffsetInFile]);
      }
    }
  }
}