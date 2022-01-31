/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.pdfbox.util;

import java.util.Base64;

/**
 * Utility functions for hex encoding.
 *
 * @author John Hewson
 */
public final class Hex
{
    private Hex() {}

    /**
     * Returns a hex string of the given byte.
     */
    public static String getString(byte b)
    {
        return Integer.toHexString(0x100 | b & 0xff).substring(1).toUpperCase();
    }

    /**
     * Returns the bytes corresponding to the ASCII hex encoding of the given byte.
     */
    public static byte[] getBytes(byte b)
    {
        return getString(b).getBytes(Charsets.US_ASCII);
    }

    /**
     * Decode a base64 String.
     *
     * @param base64Value a base64 encoded String.
     *
     * @return the decoded String as a byte array.
     *
     * @throws IllegalArgumentException if this isn't a base64 encoded string.
     */
    public static byte[] decodeBase64(String base64Value)
    {
        return Base64.getDecoder().decode(base64Value.replaceAll("\\s", ""));
    }
}
