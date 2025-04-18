/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.encryption.crypto;

import java.security.SecureRandom;

/**
 * Methods for AES/CTR encryption.
 */
public class AesCtrUtil {

  /**
   * AES block has a fixed length of 16 bytes (128 bits).
   */
  public static final int AES_BLOCK_SIZE = 16;

  /**
   * AES/CTR IV length. It is equal to {@link #AES_BLOCK_SIZE}. It is defined separately mainly for code clarity.
   */
  public static final int IV_LENGTH = AES_BLOCK_SIZE;

  /**
   * Checks a key for AES. Its length must be either 16, 24 or 32 bytes.
   */
  public static void checkAesKey(byte[] key) {
    if (key.length != 16 && key.length != 24 && key.length != 32) {
      // AES requires either 128, 192 or 256 bits keys.
      throw new IllegalArgumentException("Invalid AES key length; it must be either 128, 192 or 256 bits long");
    }
  }

  /**
   * Checks the CTR counter. It must be positive or null.
   */
  public static void checkCtrCounter(long counter) {
    if (counter < 0) {
      throw new IllegalArgumentException("Illegal counter=" + counter);
    }
  }

  /**
   * Generates a random IV for AES/CTR of length {@link #IV_LENGTH}.
   */
  public static byte[] generateRandomAesCtrIv(SecureRandom secureRandom) {
    // IV length must be the AES block size.
    // IV must be random for the CTR mode. It starts with counter 0, so it's simply IV.
    byte[] iv = new byte[IV_LENGTH];
    do {
      secureRandom.nextBytes(iv);
      // Ensure that we have enough bits left to allow the 8 bytes counter to add with the carry.
      // The high-order byte is at index 0.
      // We check that there is at least one unset bit in the 3 highest bytes. It guarantees
      // that we can add with the carry at least 5 bytes of the counter, which means we handle
      // files of at least 2^(5*8) * 2 * 16 B = 35,000 GB.
    } while (iv[0] == -1 && iv[1] == -1 && iv[2] == -1);
    return iv;
  }

  /**
   * Builds an AES/CTR IV based on the provided counter and an initial IV.
   * The built IV is the same as with {@code com.sun.crypto.provider.CounterMode.increment()}.
   */
  public static void buildAesCtrIv(byte[] initialIv, long counter, byte[] iv) {
    assert initialIv.length == IV_LENGTH && iv.length == IV_LENGTH;
    int ivIndex = iv.length;
    int counterIndex = 0;
    int sum = 0;
    while (ivIndex-- > 0) {
      // (sum >>> Byte.SIZE) is the carry for counter addition.
      sum = (initialIv[ivIndex] & 0xff) + (sum >>> Byte.SIZE);
      // Add long counter.
      if (counterIndex++ < 8) {
        sum += (byte) counter & 0xff;
        counter >>>= 8;
      }
      iv[ivIndex] = (byte) sum;
    }
  }
}