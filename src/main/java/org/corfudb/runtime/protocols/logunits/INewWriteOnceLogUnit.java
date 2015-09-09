/**
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.corfudb.runtime.protocols.logunits;

import lombok.Data;
import org.corfudb.infrastructure.thrift.Hint;
import org.corfudb.infrastructure.thrift.Hints;
import org.corfudb.infrastructure.thrift.ReadCode;
import org.corfudb.infrastructure.thrift.ReadResult;
import org.corfudb.runtime.*;
import org.corfudb.runtime.protocols.IServerProtocol;

import java.nio.ByteBuffer;
import java.util.Set;
import java.util.UUID;

/**
 * This interface represents the simplest type of stream unit.
 * Write once stream units provide these simple features:
 *
 * Write, which takes an address and payload and returns success/failure
 *          It is guranteed that any address once written to is immutable (write-once)
 * Read, which takes an address and returns a payload, or an error if
 *          nothing exists at that address.
 * Trim, which takes some offset and marks all addresses before, inclusive, as trimmed.
 *          Trimmed addresses return a trimmed error when written to or read from.
 *
 * All methods are synchronous, that is, they block until successful completion
 * of the command.
 */

public interface INewWriteOnceLogUnit extends IServerProtocol {

    @Data
    class WriteOnceLogUnitRead {
        final ReadCode result;
        final Set<UUID> streams;
        final ByteBuffer data;
        final Set<Hint> hints;

        public byte[] getDataAsArray() {
            int oldPos = data.position();
            byte[] b = new byte[data.remaining()];
            data.get(b);
            data.position(oldPos);
            return b;
        }
    }

    void write(long address, Set<UUID> streams, ByteBuffer payload) throws OverwriteException, TrimmedException, NetworkException, OutOfSpaceException;
    WriteOnceLogUnitRead read(long address) throws NetworkException;
    void trim(UUID stream, long address) throws NetworkException;
    void fillHole(long address);
    void forceGC();
}

