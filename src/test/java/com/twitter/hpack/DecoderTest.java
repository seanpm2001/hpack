/*
 * Copyright 2013 Twitter, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.twitter.hpack;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

public class DecoderTest {

  private static final int MAX_HEADER_SIZE = 8192;

  private Decoder decoder;
  private HeaderListener mockListener;

  private static String hex(String s) {
    return Hex.encodeHexString(s.getBytes());
  }

  private static byte[] getBytes(String s) {
    return s.getBytes(StandardCharsets.US_ASCII);
  }

  private void decode(String encoded) throws IOException {
    byte[] b = Hex.decodeHex(encoded.toCharArray());
    decoder.decode(new ByteArrayInputStream(b), mockListener);
  }

  @Before
  public void setUp() {
    decoder = new Decoder(true, MAX_HEADER_SIZE);
    mockListener = mock(HeaderListener.class);
  }

  @Test
  public void testIncompleteIndex() throws IOException {
    // Verify incomplete indices are unread
    byte[] compressed = Hex.decodeHex("FFF0".toCharArray());
    ByteArrayInputStream in = new ByteArrayInputStream(compressed);
    decoder.decode(in, mockListener);
    assertEquals(1, in.available());
    decoder.decode(in, mockListener);
    assertEquals(1, in.available());
  }

  @Test(expected = IOException.class)
  public void testIllegalIndex() throws IOException {
    // Index larger than the header table
    decode("FF00");
  }

  @Test(expected = IOException.class)
  public void testInsidiousIndex() throws IOException {
    // Insidious index so the last shift causes sign overflow
    decode("FF8080808008");
  }

  @Test(expected = IOException.class)
  public void testLiteralWithoutIndexingWithEmptyName() throws Exception {
    decode("400005" + hex("value"));
  }

  @Test
  public void testLiteralWithoutIndexingWithLargeName() throws Exception {
    // Ignore header name that exceeds max header size
    StringBuilder sb = new StringBuilder();
    sb.append("407F817F");
    for (int i = 0; i < 16384; i++) {
      sb.append("61"); // 'a'
    }
    sb.append("00");
    decode(sb.toString());
    verify(mockListener, never()).emitHeader((byte[]) any(), (byte[]) any());

    // Verify header block is reported as truncated
    assertTrue(decoder.endHeaderBlock(mockListener));
    verify(mockListener, never()).emitHeader((byte[]) any(), (byte[]) any());

    // Verify table is unmodified
    decode("86");
    verify(mockListener).emitHeader(getBytes(":scheme"), getBytes("http"));
    verify(mockListener).emitHeader((byte[]) any(), (byte[]) any());
  }

  @Test
  public void testLiteralWithoutIndexingWithLargeValue() throws Exception {
    // Ignore header that exceeds max header size
    StringBuilder sb = new StringBuilder();
    sb.append("4004");
    sb.append(hex("name"));
    sb.append("7F813F");
    for (int i = 0; i < 8192; i++) {
      sb.append("61"); // 'a'
    }
    decode(sb.toString());
    verify(mockListener, never()).emitHeader((byte[]) any(), (byte[]) any());

    // Verify header block is reported as truncated
    assertTrue(decoder.endHeaderBlock(mockListener));
    verify(mockListener, never()).emitHeader((byte[]) any(), (byte[]) any());

    // Verify table is unmodified
    decode("86");
    verify(mockListener).emitHeader(getBytes(":scheme"), getBytes("http"));
    verify(mockListener).emitHeader((byte[]) any(), (byte[]) any());
  }

  @Test(expected = IOException.class)
  public void testLiteralWithIncrementalIndexingWithEmptyName() throws Exception {
    decode("000005" + hex("value"));
  }

  @Test
  public void testLiteralWithIncrementalIndexingCompleteEviction() throws Exception {
    // Verify indexed host header
    decode("0004" + hex("name") + "05" + hex("value"));
    verify(mockListener).emitHeader(getBytes("name"), getBytes("value"));
    verify(mockListener).emitHeader((byte[]) any(), (byte[]) any());

    reset(mockListener);
    assertFalse(decoder.endHeaderBlock(mockListener));
    verify(mockListener, never()).emitHeader((byte[]) any(), (byte[]) any());

    // Verify header is added to the reference set
    assertFalse(decoder.endHeaderBlock(mockListener));
    verify(mockListener).emitHeader(getBytes("name"), getBytes("value"));
    verify(mockListener).emitHeader((byte[]) any(), (byte[]) any());

    reset(mockListener);
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 4096; i++) {
      sb.append("a");
    }
    String value = sb.toString();
    sb = new StringBuilder();
    sb.append("027F811F");
    for (int i = 0; i < 4096; i++) {
      sb.append("61"); // 'a'
    }
    decode(sb.toString());
    verify(mockListener).emitHeader(getBytes(":authority"), getBytes(value));
    verify(mockListener).emitHeader((byte[]) any(), (byte[]) any());

    reset(mockListener);
    assertFalse(decoder.endHeaderBlock(mockListener));
    verify(mockListener, never()).emitHeader((byte[]) any(), (byte[]) any());

    // Verify all headers has been evicted from table
    assertFalse(decoder.endHeaderBlock(mockListener));
    verify(mockListener, never()).emitHeader((byte[]) any(), (byte[]) any());

    // Verify next header is inserted at index 0
    // remove from reference set, insert into reference set and emit
    decode("0004" + hex("name") + "05" + hex("value") + "8181");
    verify(mockListener, times(2)).emitHeader(getBytes("name"), getBytes("value"));
    verify(mockListener, times(2)).emitHeader((byte[]) any(), (byte[]) any());
  }

  @Test
  public void testLiteralWithIncrementalIndexingWithLargeName() throws Exception {
    // Ignore header name that exceeds max header size
    StringBuilder sb = new StringBuilder();
    sb.append("007F817F");
    for (int i = 0; i < 16384; i++) {
      sb.append("61"); // 'a'
    }
    sb.append("00");
    decode(sb.toString());
    verify(mockListener, never()).emitHeader((byte[]) any(), (byte[]) any());

    // Verify header block is reported as truncated
    assertTrue(decoder.endHeaderBlock(mockListener));
    verify(mockListener, never()).emitHeader((byte[]) any(), (byte[]) any());

    // Verify next header is inserted at index 0
    // remove from reference set, insert into reference set and emit
    decode("0004" + hex("name") + "05" + hex("value") + "8181");
    verify(mockListener, times(2)).emitHeader(getBytes("name"), getBytes("value"));
    verify(mockListener, times(2)).emitHeader((byte[]) any(), (byte[]) any());
  }

  @Test
  public void testLiteralWithIncrementalIndexingWithLargeValue() throws Exception {
    // Ignore header that exceeds max header size
    StringBuilder sb = new StringBuilder();
    sb.append("0004");
    sb.append(hex("name"));
    sb.append("7F813F");
    for (int i = 0; i < 8192; i++) {
      sb.append("61"); // 'a'
    }
    decode(sb.toString());
    verify(mockListener, never()).emitHeader((byte[]) any(), (byte[]) any());

    // Verify header block is reported as truncated
    assertTrue(decoder.endHeaderBlock(mockListener));
    verify(mockListener, never()).emitHeader((byte[]) any(), (byte[]) any());

    // Verify next header is inserted at index 0
    // remove from reference set, insert into reference set and emit
    decode("0004" + hex("name") + "05" + hex("value") + "8181");
    verify(mockListener, times(2)).emitHeader(getBytes("name"), getBytes("value"));
    verify(mockListener, times(2)).emitHeader((byte[]) any(), (byte[]) any());
  }
}
