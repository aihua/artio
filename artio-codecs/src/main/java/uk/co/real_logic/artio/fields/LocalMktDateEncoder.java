/*
 * Copyright 2015-2017 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.artio.fields;

import org.agrona.concurrent.UnsafeBuffer;
import uk.co.real_logic.artio.util.MutableAsciiBuffer;

/**
 * Equivalent to encoding a Java format string of "yyyyMMdd", allocation free.
 */
public final class LocalMktDateEncoder
{
    public static final int LENGTH = 8;
    public static final int MIN_EPOCH_DAYS = LocalMktDateDecoder.MIN_EPOCH_DAYS;
    public static final int MAX_EPOCH_DAYS = LocalMktDateDecoder.MAX_EPOCH_DAYS;

    private final UnsafeBuffer buffer = new UnsafeBuffer(0, 0);
    private final MutableAsciiBuffer flyweight = new MutableAsciiBuffer(buffer);

    public int encode(final int localEpochDays, final byte[] bytes)
    {
        buffer.wrap(bytes);
        return encode(localEpochDays, flyweight, 0);
    }

    public static int encode(final int localEpochDays, final MutableAsciiBuffer string, final int offset)
    {
        if (localEpochDays < MIN_EPOCH_DAYS || localEpochDays > MAX_EPOCH_DAYS)
        {
            throw new IllegalArgumentException(localEpochDays + " is outside of the valid range for this encoder");
        }

        CalendricalUtil.encodeDate(localEpochDays, string, offset);

        return LENGTH;
    }
}
