/*
 * Copyright 2014 Higher Frequency Trading http://www.higherfrequencytrading.com
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

package net.openhft.chronicle.map;

import net.openhft.lang.Maths;
import net.openhft.lang.collection.DirectBitSet;
import net.openhft.lang.collection.SingleThreadedDirectBitSet;
import net.openhft.lang.io.*;
import net.openhft.lang.io.serialization.BytesMarshallable;
import net.openhft.lang.io.serialization.BytesMarshaller;
import net.openhft.lang.io.serialization.ObjectFactory;
import net.openhft.lang.io.serialization.ObjectSerializer;
import net.openhft.lang.io.serialization.impl.VanillaBytesMarshallerFactory;
import net.openhft.lang.model.Byteable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.nio.channels.FileChannel;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static java.lang.Thread.currentThread;
import static net.openhft.chronicle.map.VanillaChronicleMap.Hasher.hash;


class VanillaChronicleMap<K, V> extends AbstractMap<K, V>
        implements ChronicleMap<K, V>, Serializable {
    private static final long serialVersionUID = 0L;
    private static final Logger LOG = LoggerFactory.getLogger(VanillaChronicleMap.class);

    /**
     * Because DirectBitSet implementations couldn't find more than 64 continuous clear or set bits.
     */
    private static final int MAX_ENTRY_OVERSIZE_FACTOR = 64;

    private static final int SEGMENT_HEADER = 64;
    final Class<K> kClass;
    final BytesMarshaller<K> keyMarshaller;
    final BytesMarshaller<V> valueMarshaller;
    final int metaDataBytes;
    final Hasher hasher;
    //   private final int replicas;
    final int entrySize;
    final Alignment alignment;
    final int actualSegments;
    final int entriesPerSegment;
    final MapEventListener<K, V, ChronicleMap<K, V>> eventListener;
    // if set the ReturnsNull fields will cause some functions to return NULL
    // rather than as returning the Object can be expensive for something you probably don't use.
    final boolean putReturnsNull;
    final boolean removeReturnsNull;
    private final int bufferAllocationFactor;
    private final ObjectSerializer objectSerializer;
    private final Class<V> vClass;
    private final ObjectFactory<V> valueFactory;
    private final long lockTimeOutNS;
    private final MapErrorListener errorListener;
    transient Segment[] segments; // non-final for close()
    // non-final for close() and because it is initialized out of constructor
    transient BytesStore ms;
    transient long headerSize;
    transient Set<Map.Entry<K, V>> entrySet;
    private transient ThreadLocal<DirectBytes> localBufferForKeys;
    private transient ThreadLocal<DirectBytes> localBufferForValues;

    public VanillaChronicleMap(ChronicleMapBuilder<K, V> builder)
            throws IOException {
        bufferAllocationFactor = figureBufferAllocationFactor(builder);
        this.kClass = builder.keyClass;
        this.keyMarshaller = builder.keyMarshaller();
        this.vClass = builder.valueClass;
        this.valueMarshaller = builder.valueMarshaller();
        this.valueFactory = builder.valueFactory();

        lockTimeOutNS = builder.lockTimeOut(TimeUnit.NANOSECONDS);

        //  this.replicas = builder.replicas();
        this.entrySize = builder.alignedEntrySize();
        this.alignment = builder.entryAndValueAlignment();

        this.errorListener = builder.errorListener();
        this.putReturnsNull = builder.putReturnsNull();
        this.removeReturnsNull = builder.removeReturnsNull();
        this.objectSerializer = builder.objectSerializer();

        this.actualSegments = builder.actualSegments();
        this.entriesPerSegment = builder.actualEntriesPerSegment();
        this.metaDataBytes = builder.metaDataBytes();
        this.eventListener = builder.eventListener();

        int hashMask = useSmallMultiMaps() ? 0xFFFF : ~0;
        this.hasher = new Hasher(actualSegments, hashMask);

        initTransients();
    }

    private static int figureBufferAllocationFactor(ChronicleMapBuilder builder) {
        // if expected map size is about 1000, seems rather wasteful to allocate
        // key and value serialization buffers each x64 of expected entry size..
        return (int) Math.min(Math.max(2L, builder.entries() >> 10),
                MAX_ENTRY_OVERSIZE_FACTOR);
    }

    /**
     * @param size positive number
     * @return number of bytes taken by {@link net.openhft.lang.io.AbstractBytes#writeStopBit(long)} applied
     * to {@code size}
     */
    static int expectedStopBits(long size) {
        if (size <= 127)
            return 1;
        // numberOfLeadingZeros is cheap intrinsic on modern CPUs
        // integral division is not... but there is no choice
        return ((70 - Long.numberOfLeadingZeros(size)) / 7);
    }

    /**
     * Cache line alignment, assuming 64-byte cache lines.
     */
    static long align64(long l) {
        return (l + 63) & ~63;
    }

    void initTransients() {
        @SuppressWarnings("unchecked")
        Segment[] ss = (Segment[]) Array.newInstance(segmentType(), actualSegments);
        this.segments = ss;
        localBufferForKeys = new ThreadLocal<DirectBytes>();
        localBufferForValues = new ThreadLocal<DirectBytes>();
    }

    Class segmentType() {
        return Segment.class;
    }

    long createMappedStoreAndSegments(BytesStore bytesStore) throws IOException {
        this.ms = bytesStore;

        onHeaderCreated();

        long offset = getHeaderSize();
        long segmentSize = segmentSize();
        for (int i = 0; i < this.segments.length; i++) {
            this.segments[i] = createSegment((NativeBytes) ms.bytes(offset, segmentSize), i);
            offset += segmentSize;
        }
        return offset;
    }

    long createMappedStoreAndSegments(File file) throws IOException {
        return createMappedStoreAndSegments(new MappedStore(file, FileChannel.MapMode.READ_WRITE,
                sizeInBytes(), objectSerializer));
    }

    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        initTransients();
    }

    /**
     * called when the header is created
     */
    void onHeaderCreated() {

    }

    long getHeaderSize() {
        return headerSize;
    }

    Segment createSegment(NativeBytes bytes, int index) {
        return new Segment(bytes, index);
    }

    @Override
    public File file() {
        return ms.file();
    }

    long sizeInBytes() {
        return getHeaderSize() + segments.length * segmentSize();
    }

    long sizeOfMultiMap() {
        return useSmallMultiMaps() ?
                VanillaShortShortMultiMap.sizeInBytes(entriesPerSegment) :
                VanillaIntIntMultiMap.sizeInBytes(entriesPerSegment);
    }

    long sizeOfMultiMapBitSet() {
        return useSmallMultiMaps() ?
                VanillaShortShortMultiMap.sizeOfBitSetInBytes(entriesPerSegment) :
                VanillaIntIntMultiMap.sizeOfBitSetInBytes(entriesPerSegment);
    }

    boolean useSmallMultiMaps() {
        return entriesPerSegment <= (1 << 16);
    }

    long sizeOfBitSets() {
        return align64(entriesPerSegment / 8);
    }

    int numberOfBitSets() {
        return 1; // for free list
        //  + (replicas > 0 ? 1 : 0) // deleted set
        //   + replicas; // to notify each replica of a change.
    }

    long segmentSize() {
        long ss = SEGMENT_HEADER
                + align64(sizeOfMultiMap() + sizeOfMultiMapBitSet()) * multiMapsPerSegment()
                + numberOfBitSets() * sizeOfBitSets() // the free list and 0+ dirty lists.
                + sizeOfEntriesInSegment();
        if ((ss & 63) != 0)
            throw new AssertionError();

        // Say, there is 32 KB L1 cache with 2(4, 8) way set associativity, 64-byte lines.
        // It means there are 32 * 1024 / 64 / 2(4, 8) = 256(128, 64) sets,
        // i. e. each way (bank) contains 256(128, 64) lines. (L2 and L3 caches has more sets.)
        // If segment size in lines multiplied by 2^n is divisible by set size,
        // every 2^n-th segment header fall into the same set.
        // To break this up we make segment size odd in lines, in this case only each
        // 256(128, 64)-th segment header fall into the same set.

        // If there are 64 sets in L1, it should be 8- or much less likely 4-way, and segments
        // collision by pairs is not so terrible.

        // if the size is a multiple of 4096 or slightly more. Make sure it is at least 64 more than a multiple.
        if ((ss & 4093) < 64)
            ss = (ss & ~63) + 64;

        return ss;
    }

    int multiMapsPerSegment() {
        return 1;
    }

    private long sizeOfEntriesInSegment() {
        return align64((long) entriesPerSegment * entrySize);
    }

    @Override
    public void close() {
        if (ms == null)
            return;
        ms.free();
        segments = null;
        ms = null;
    }

    MapEventListener<K, V, ChronicleMap<K, V>> eventListener() {
        return eventListener;
    }

    private DirectBytes acquireBufferForKey() {
        DirectBytes buffer = localBufferForKeys.get();
        if (buffer == null) {
            buffer = new DirectStore(ms.objectSerializer(),
                    entrySize * bufferAllocationFactor, false).bytes();
            localBufferForKeys.set(buffer);
        } else {
            buffer.clear();
        }
        return buffer;
    }

    private DirectBytes acquireBufferForValue() {
        DirectBytes buffer = localBufferForValues.get();
        if (buffer == null) {
            buffer = new DirectStore(ms.objectSerializer(),
                    entrySize * bufferAllocationFactor, false).bytes();
            localBufferForValues.set(buffer);
        } else {
            buffer.clear();
        }
        return buffer;
    }

    void checkKey(Object key) {
        if (!kClass.isInstance(key)) {
            // key.getClass will cause NPE exactly as needed
            throw new ClassCastException("Key must be a " + kClass.getName() +
                    " but was a " + key.getClass());
        }
    }

    void checkValue(Object value) {
        if (!vClass.isInstance(value)) {
            throw new ClassCastException("Value must be a " + vClass.getName() +
                    " but was a " + value.getClass());
        }
    }

    @Override
    public V put(K key, V value) {
        return put0(key, value, true);
    }

    @Override
    public V putIfAbsent(K key, V value) {
        return put0(key, value, false);
    }

    private V put0(K key, V value, boolean replaceIfPresent) {
        checkKey(key);
        checkValue(value);
        Bytes keyBytes = getKeyAsBytes(key);
        long hash = hash(keyBytes);
        int segmentNum = hasher.getSegment(hash);
        int segmentHash = hasher.segmentHash(hash);
        return segments[segmentNum].put(keyBytes, key, value, segmentHash, replaceIfPresent);
    }

    DirectBytes getKeyAsBytes(K key) {
        DirectBytes buffer = acquireBufferForKey();
        keyMarshaller.write(buffer, key);
        buffer.flip();
        return buffer;
    }

    DirectBytes getValueAsBytes(V value) {
        DirectBytes buffer = acquireBufferForValue();
        buffer.clear();
        valueMarshaller.write(buffer, value);
        buffer.flip();
        return buffer;
    }

    @Override
    public V get(Object key) {
        return lookupUsing((K) key, null, false);
    }

    @Override
    public V getUsing(K key, V value) {
        return lookupUsing(key, value, false);
    }

    @Override
    public V acquireUsing(K key, V value) {
        return lookupUsing(key, value, true);
    }

    V lookupUsing(K key, V value, boolean create) {
        checkKey(key);
        Bytes keyBytes = getKeyAsBytes(key);
        long hash = hash(keyBytes);
        int segmentNum = hasher.getSegment(hash);
        int segmentHash = hasher.segmentHash(hash);
        return segments[segmentNum].acquire(keyBytes, key, value, segmentHash, create);
    }

    @Override
    public boolean containsKey(final Object key) {
        checkKey(key);
        Bytes keyBytes = getKeyAsBytes((K) key);
        long hash = hash(keyBytes);
        int segmentNum = hasher.getSegment(hash);
        int segmentHash = hasher.segmentHash(hash);

        return segments[segmentNum].containsKey(keyBytes, segmentHash);
    }

    @Override
    public void clear() {
        for (Segment segment : segments)
            segment.clear();
    }

    @NotNull
    @Override
    public Set<Entry<K, V>> entrySet() {
        return (entrySet != null) ? entrySet : (entrySet = new EntrySet());
    }


    /**
     * @throws NullPointerException if the specified key is null
     */
    @Override
    public V remove(final Object key) {
        return removeIfValueIs(key, null);
    }

    /**
     * @throws NullPointerException if the specified key is null
     */
    @Override
    public boolean remove(final Object key, final Object value) {
        if (value == null)
            return false; // CHM compatibility; I would throw NPE
        return removeIfValueIs(key, (V) value) != null;
    }


    /**
     * removes ( if there exists ) an entry from the map, if the {@param key} and {@param expectedValue} match
     * that of a maps.entry. If the {@param expectedValue} equals null then ( if there exists ) an entry whose
     * key equals {@param key} this is removed.
     *
     * @param key           the key of the entry to remove
     * @param expectedValue null if not required
     * @return true if and entry was removed
     */
    V removeIfValueIs(final Object key, final V expectedValue) {
        checkKey(key);
        Bytes keyBytes = getKeyAsBytes((K) key);
        long hash = hash(keyBytes);
        int segmentNum = hasher.getSegment(hash);
        int segmentHash = hasher.segmentHash(hash);
        return segments[segmentNum].remove(keyBytes, (K) key, expectedValue, segmentHash);
    }

    /**
     * @throws NullPointerException if any of the arguments are null
     */
    @Override
    public boolean replace(final K key, final V oldValue, final V newValue) {
        checkValue(oldValue);
        return oldValue.equals(replaceIfValueIs(key, oldValue, newValue));
    }


    /**
     * @throws NullPointerException if the specified key or value is null
     */
    @Override
    public V replace(final K key, final V value) {
        return replaceIfValueIs(key, null, value);
    }

    @Override
    public long longSize() {
        long result = 0;

        for (final Segment segment : this.segments) {
            result += segment.getSize();
        }

        return result;
    }

    @Override
    public int size() {
        long size = longSize();
        return size > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) size;
    }

    /**
     * replace the value in a map, only if the existing entry equals {@param existingValue}
     *
     * @param key           the key into the map
     * @param existingValue the expected existing value in the map ( could be null when we don't wish to do
     *                      this check )
     * @param newValue      the new value you wish to store in the map
     * @return the value that was replaced
     */
    V replaceIfValueIs(@net.openhft.lang.model.constraints.NotNull final K key, final V existingValue,
                       final V newValue) {
        checkKey(key);
        checkValue(newValue);
        Bytes keyBytes = getKeyAsBytes(key);
        long hash = hash(keyBytes);
        int segmentNum = hasher.getSegment(hash);
        int segmentHash = hasher.segmentHash(hash);
        return segments[segmentNum].replace(keyBytes, key, existingValue, newValue, segmentHash);
    }

    /**
     * For testing
     */
    void checkConsistency() {
        for (Segment segment : segments) {
            segment.checkConsistency();
        }
    }


    static final class Hasher implements Serializable {
        private static final long serialVersionUID = 0L;
        private final int segments;
        private final int bits;
        private final int mask;

        Hasher(int segments, int mask) {
            this.segments = segments;
            this.bits = Maths.intLog2(segments);
            this.mask = mask;
        }

        static long hash(Bytes bytes) {
            long h = 0;
            long i = bytes.position();
            long limit = bytes.limit(); // clustering.
            for (; i < limit - 7; i += 8)
                h = 1011001110001111L * h + bytes.readLong(i);
            for (; i < limit - 1; i += 2)
                h = 101111 * h + bytes.readShort(i);
            if (i < limit)
                h = 2111 * h + bytes.readByte(i);
            h *= 11018881818881011L;
            h ^= (h >>> 41) ^ (h >>> 21);
            //System.out.println(bytes + " => " + Long.toHexString(h));
            return h;
        }

        int segmentHash(long hash) {
            return (int) (hash >>> bits) & mask;
        }

        int getSegment(long hash) {
            return (int) (hash & (segments - 1));
        }
    }

    // these methods should be package local, not public or private.
    class Segment implements SharedSegment {
        /*
        The entry format is
        - stop-bit encoded length for key
        - bytes for the key
        - stop-bit encoded length of the value
        - bytes for the value.
         */
        static final int LOCK_OFFSET = 0; // 64-bit
        static final int SIZE_OFFSET = LOCK_OFFSET + 8; // 32-bit
        static final int PAD1_OFFSET = SIZE_OFFSET + 4; // 32-bit
        static final int REPLICA_OFFSET = PAD1_OFFSET + 4; // 64-bit

        final NativeBytes bytes;
        final MultiStoreBytes tmpBytes = new MultiStoreBytes();
        final long entriesOffset;
        private final int index;
        private final SingleThreadedDirectBitSet freeList;
        private IntIntMultiMap hashLookup;
        private int nextPosToSearchFrom = 0;


        /**
         * @param bytes
         * @param index the index of this segment held by the map
         */
        Segment(NativeBytes bytes, int index) {
            this.bytes = bytes;
            this.index = index;

            long start = bytes.startAddr() + SEGMENT_HEADER;
            createHashLookups(start);
            start += align64(sizeOfMultiMap() + sizeOfMultiMapBitSet()) * multiMapsPerSegment();
            final NativeBytes bsBytes = new NativeBytes(
                    tmpBytes.objectSerializer(), start, start + sizeOfBitSets(), null);
            freeList = new SingleThreadedDirectBitSet(bsBytes);
            start += numberOfBitSets() * sizeOfBitSets();
            entriesOffset = start - bytes.startAddr();
            assert bytes.capacity() >= entriesOffset + entriesPerSegment * entrySize;
        }

        void createHashLookups(long start) {
            hashLookup = createMultiMap(start);
        }

        public IntIntMultiMap getHashLookup() {
            return hashLookup;
        }


        IntIntMultiMap createMultiMap(long start) {
            final NativeBytes multiMapBytes =
                    new NativeBytes(new VanillaBytesMarshallerFactory(), start,
                            start = start + sizeOfMultiMap(), null);

            final NativeBytes sizeOfMultiMapBitSetBytes =
                    new NativeBytes(new VanillaBytesMarshallerFactory(), start,
                            start + sizeOfMultiMapBitSet(), null);
            multiMapBytes.load();
            return useSmallMultiMaps() ?
                    new VanillaShortShortMultiMap(multiMapBytes, sizeOfMultiMapBitSetBytes) :
                    new VanillaIntIntMultiMap(multiMapBytes, sizeOfMultiMapBitSetBytes);
        }

        public int getIndex() {
            return index;
        }


        /* Methods with private access modifier considered private to Segment
         * class, although Java allows to access them from outer class anyway.
         */

        /**
         * increments the size by one
         */
        void incrementSize() {
            this.bytes.addInt(SIZE_OFFSET, 1);
        }

        void resetSize() {
            this.bytes.writeInt(SIZE_OFFSET, 0);
        }

        /**
         * decrements the size by one
         */
        void decrementSize() {
            this.bytes.addInt(SIZE_OFFSET, -1);
        }

        /**
         * reads the the number of entries in this segment
         */
        int getSize() {
            // any negative value is in error state.
            return Math.max(0, this.bytes.readVolatileInt(SIZE_OFFSET));
        }


        public void lock() throws IllegalStateException {
            while (true) {
                final boolean success = bytes.tryLockNanosLong(LOCK_OFFSET, lockTimeOutNS);
                if (success) return;
                if (currentThread().isInterrupted()) {
                    throw new IllegalStateException(new InterruptedException("Unable to obtain lock, interrupted"));
                } else {
                    errorListener.onLockTimeout(bytes.threadIdForLockLong(LOCK_OFFSET));
                    bytes.resetLockLong(LOCK_OFFSET);
                }
            }
        }

        public void unlock() {
            try {
                bytes.unlockLong(LOCK_OFFSET);
            } catch (IllegalMonitorStateException e) {
                errorListener.errorOnUnlock(e);
            }
        }

        public long offsetFromPos(long pos) {
            return entriesOffset + pos * entrySize;
        }

        long posFromOffset(long offset) {
            return (offset - entriesOffset) / entrySize;
        }


        public MultiStoreBytes entry(long offset) {
            return reuse(tmpBytes, offset);
        }

        private MultiStoreBytes reuse(MultiStoreBytes entry, long offset) {
            offset += metaDataBytes;
            entry.storePositionAndSize(bytes, offset,
                    // "Infinity". Limit not used when treating entries as
                    // possibly oversized
                    bytes.limit() - offset);
            return entry;
        }

        long entryStartAddr(long offset) {
            // entry.address() points to "needed" start addr + metaDataBytes
            return bytes.startAddr() + offset;
        }

        private long entrySize(long keyLen, long valueLen) {
            return alignment.alignAddr(metaDataBytes +
                    expectedStopBits(keyLen) + keyLen +
                    expectedStopBits(valueLen)) + valueLen;
        }

        int inBlocks(long sizeInBytes) {
            if (sizeInBytes <= entrySize)
                return 1;
            // int division is MUCH faster than long on Intel CPUs
            sizeInBytes -= 1;
            if (sizeInBytes <= Integer.MAX_VALUE)
                return (((int) sizeInBytes) / entrySize) + 1;
            return (int) (sizeInBytes / entrySize) + 1;
        }

        /**
         * Used to acquire an object of type V from the Segment. <p/> {@code usingValue} is reused to read the
         * value if key is present in this Segment, if key is absent in this Segment: <p/> <ol><li>If {@code
         * create == false}, just {@code null} is returned (except when event listener provides a value "on
         * get missing" - then it is put into this Segment for the key).</li> <p/> <li>If {@code create ==
         * true}, {@code usingValue} or a newly created instance of value class, if {@code usingValue ==
         * null}, is put into this Segment for the key.</li></ol>
         *
         * @param keyBytes serialized {@code key}
         * @param hash2    a hash code related to the {@code keyBytes}
         * @return the value which is finally associated with the given key in this Segment after execution of
         * this method, or {@code null}.
         */
        V acquire(Bytes keyBytes, K key, V usingValue, int hash2, boolean create) {
            lock();
            try {
                MultiStoreBytes entry = tmpBytes;
                long offset = searchKey(keyBytes, hash2, entry, hashLookup);
                if (offset >= 0) {
                    return onKeyPresentOnAcquire(key, usingValue, offset, entry);
                } else {
                    usingValue = tryObtainUsingValueOnAcquire(keyBytes, key, usingValue, create);
                    if (usingValue != null) {
                        // If `create` is false, this method was called from get() or getUsing()
                        // and non-null `usingValue` was returned by notifyMissed() method.
                        // This "missed" default value is considered as genuine value
                        // rather than "using" container to fill up, even if it implements Byteable.
                        offset = putEntry(keyBytes, usingValue, create);
                        incrementSize();
                        notifyPut(offset, true, key, usingValue, posFromOffset(offset));
                        return usingValue;
                    } else {
                        return null;
                    }
                }
            } finally {
                unlock();
            }
        }

        long searchKey(Bytes keyBytes, int hash2,
                       MultiStoreBytes entry, IntIntMultiMap hashLookup) {
            long keyLen = keyBytes.remaining();
            hashLookup.startSearch(hash2);
            for (int pos; (pos = hashLookup.nextPos()) >= 0; ) {
                long offset = offsetFromPos(pos);
                reuse(entry, offset);
                if (!keyEquals(keyBytes, keyLen, entry))
                    continue;
                // key is found
                entry.skip(keyLen);
                return offset;
            }
            // key is not found
            return -1L;
        }

        V onKeyPresentOnAcquire(K key, V usingValue, long offset, NativeBytes entry) {
            V v = readValue(entry, usingValue);
            notifyGet(offset, key, v);
            return v;
        }

        V tryObtainUsingValueOnAcquire(Bytes keyBytes, K key, V usingValue, boolean create) {
            if (create) {
                if (usingValue != null) {
                    return usingValue;
                } else {
                    try {
                        return valueFactory.create();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }
            } else {
                if (usingValue instanceof Byteable)
                    ((Byteable) usingValue).bytes(null, 0);
                return notifyMissed(keyBytes, key, usingValue);
            }
        }

        V put(Bytes keyBytes, K key, V value, int hash2, boolean replaceIfPresent) {
            lock();
            try {
                long keyLen = keyBytes.remaining();
                hashLookup.startSearch(hash2);
                for (int pos; (pos = hashLookup.nextPos()) >= 0; ) {
                    long offset = offsetFromPos(pos);
                    NativeBytes entry = entry(offset);
                    if (!keyEquals(keyBytes, keyLen, entry))
                        continue;
                    // key is found
                    entry.skip(keyLen);
                    if (replaceIfPresent) {
                        return replaceValueOnPut(key, value, entry, pos, offset, !putReturnsNull, hashLookup);
                    } else {
                        return putReturnsNull ? null : readValue(entry, null);
                    }
                }
                // key is not found
                long offset = putEntry(keyBytes, value, false);
                incrementSize();
                notifyPut(offset, true, key, value, posFromOffset(offset));
                return null;
            } finally {
                unlock();
            }
        }

        V replaceValueOnPut(K key, V value, NativeBytes entry, int pos, long offset,
                            boolean readPrevValue, IntIntMultiMap searchedHashLookup) {
            long valueLenPos = entry.position();
            long valueLen = readValueLen(entry);
            long entryEndAddr = entry.positionAddr() + valueLen;
            V prevValue = null;
            if (readPrevValue)
                prevValue = readValue(entry, null, valueLen);

            // putValue may relocate entry and change offset
            offset = putValue(pos, offset, entry, valueLenPos, entryEndAddr, value, searchedHashLookup);
            notifyPut(offset, false, key, value, posFromOffset(offset));
            return prevValue;
        }

        /**
         * Puts entry. If {@code value} implements {@link net.openhft.lang.model.Byteable} interface and
         * {@code usingValue} is {@code true}, the value is backed with the bytes of this entry.
         *
         * @param keyBytes   serialized key
         * @param value      the value to put
         * @param usingValue {@code true} if the value should be backed with the bytes of the entry, if it
         *                   implements {@link net.openhft.lang.model.Byteable} interface, {@code false} if it
         *                   should put itself
         * @return offset of the written entry in the Segment bytes
         */
        private long putEntry(Bytes keyBytes, V value, boolean usingValue) {
            long keyLen = keyBytes.remaining();

            // "if-else polymorphism" is not very beautiful, but allows to
            // reuse the rest code of this method and doesn't hurt performance.
            boolean byteableValue = usingValue && value instanceof Byteable;
            long valueLen;
            Bytes valueBytes = null;
            Byteable valueAsByteable = null;
            if (!byteableValue) {
                valueBytes = getValueAsBytes(value);
                valueLen = valueBytes.remaining();
            } else {
                valueAsByteable = (Byteable) value;
                valueLen = valueAsByteable.maxSize();
            }

            long entrySize = entrySize(keyLen, valueLen);
            int pos = alloc(inBlocks(entrySize));
            long offset = offsetFromPos(pos);
            clearMetaData(offset);
            NativeBytes entry = entry(offset);

            entry.writeStopBit(keyLen);
            entry.write(keyBytes);

            writeValueOnPutEntry(valueLen, valueBytes, valueAsByteable, entry);
            hashLookup.putAfterFailedSearch(pos);
            return offset;
        }

        void writeValueOnPutEntry(long valueLen, @Nullable Bytes valueBytes,
                                  @Nullable Byteable valueAsByteable, NativeBytes entry) {
            entry.writeStopBit(valueLen);
            alignment.alignPositionAddr(entry);

            if (valueBytes != null) {
                entry.write(valueBytes);
            } else {
                assert valueAsByteable != null;
                long valueOffset = entry.positionAddr() - bytes.address();
                bytes.zeroOut(valueOffset, valueOffset + valueLen);
                valueAsByteable.bytes(bytes, valueOffset);
            }
        }

        void clearMetaData(long offset) {
            if (metaDataBytes > 0)
                bytes.zeroOut(offset, offset + metaDataBytes);
        }

        int alloc(int blocks) {
            int ret = (int) freeList.setNextNContinuousClearBits(nextPosToSearchFrom,
                    blocks);
            if (ret == DirectBitSet.NOT_FOUND) {
                ret = (int) freeList.setNextNContinuousClearBits(0, blocks);
                if (ret == DirectBitSet.NOT_FOUND) {
                    if (blocks == 1) {
                        throw new IllegalArgumentException(
                                "Segment is full, no free entries found");
                    } else {
                        throw new IllegalArgumentException(
                                "Segment is full or has no ranges of " + blocks
                                        + " continuous free blocks"
                        );
                    }
                }
            }
            // if bit at nextPosToSearchFrom is clear, it was skipped because
            // more than 1 block was requested. Don't move nextPosToSearchFrom
            // in this case. blocks == 1 clause is just a fast path.
            if (blocks == 1 || freeList.isSet(nextPosToSearchFrom))
                nextPosToSearchFrom = ret + blocks;
            return ret;
        }

        private boolean realloc(int fromPos, int oldBlocks, int newBlocks) {
            if (freeList.allClear(fromPos + oldBlocks, fromPos + newBlocks)) {
                freeList.set(fromPos + oldBlocks, fromPos + newBlocks);
                return true;
            } else {
                return false;
            }
        }

        void free(int fromPos, int blocks) {
            freeList.clear(fromPos, fromPos + blocks);
            if (fromPos < nextPosToSearchFrom)
                nextPosToSearchFrom = fromPos;
        }

        V readValue(NativeBytes entry, V value) {
            return readValue(entry, value, readValueLen(entry));
        }

        long readValueLen(Bytes entry) {
            long valueLen = entry.readStopBit();
            alignment.alignPositionAddr(entry);
            return valueLen;
        }

        /**
         * TODO use the value length to limit reading
         *
         * @param value the object to reuse (if possible), if {@code null} a new object is created
         */
        V readValue(NativeBytes entry, V value, long valueLen) {
            bytes.positionAddr(entry.positionAddr());
            return valueMarshaller.read(bytes, value);
        }

        boolean keyEquals(Bytes keyBytes, long keyLen, Bytes entry) {
            return keyLen == entry.readStopBit() && entry.startsWith(keyBytes);
        }

        /**
         * Removes a key (or key-value pair) from the Segment. <p/> The entry will only be removed if {@code
         * expectedValue} equals to {@code null} or the value previously corresponding to the specified key.
         *
         * @param keyBytes bytes of the key to remove
         * @param hash2    a hash code related to the {@code keyBytes}
         * @return the value of the entry that was removed if the entry corresponding to the {@code keyBytes}
         * exists and {@link #removeReturnsNull} is {@code false}, {@code null} otherwise
         */
        V remove(Bytes keyBytes, K key, V expectedValue, int hash2) {
            lock();
            try {
                long keyLen = keyBytes.remaining();
                hashLookup.startSearch(hash2);
                for (int pos; (pos = hashLookup.nextPos()) >= 0; ) {
                    long offset = offsetFromPos(pos);
                    NativeBytes entry = entry(offset);
                    if (!keyEquals(keyBytes, keyLen, entry))
                        continue;
                    // key is found
                    entry.skip(keyLen);
                    long valueLen = readValueLen(entry);
                    long entryEndAddr = entry.positionAddr() + valueLen;
                    V valueRemoved = expectedValue != null || !removeReturnsNull
                            ? readValue(entry, null, valueLen) : null;
                    if (expectedValue != null && !expectedValue.equals(valueRemoved))
                        return null;
                    hashLookup.removePrevPos();
                    decrementSize();
                    free(pos, inBlocks(entryEndAddr - entryStartAddr(offset)));
                    notifyRemoved(offset, key, valueRemoved, pos);
                    return valueRemoved;
                }
                // key is not found
                return null;
            } finally {
                unlock();
            }
        }

        boolean containsKey(Bytes keyBytes, int hash2) {
            lock();
            try {
                long keyLen = keyBytes.remaining();
                IntIntMultiMap hashLookup = containsKeyHashLookup();
                hashLookup.startSearch(hash2);
                for (int pos; (pos = hashLookup.nextPos()) >= 0; ) {
                    Bytes entry = entry(offsetFromPos(pos));
                    if (keyEquals(keyBytes, keyLen, entry))
                        return true;
                }
                return false;
            } finally {
                unlock();
            }
        }

        IntIntMultiMap containsKeyHashLookup() {
            return hashLookup;
        }

        /**
         * Replaces the specified value for the key with the given value. <p/> {@code newValue} is set only if
         * the existing value corresponding to the specified key is equal to {@code expectedValue} or {@code
         * expectedValue == null}.
         *
         * @param hash2 a hash code related to the {@code keyBytes}
         * @return the replaced value or {@code null} if the value was not replaced
         */
        V replace(Bytes keyBytes, K key, V expectedValue, V newValue, int hash2) {
            lock();
            try {
                long keyLen = keyBytes.remaining();
                hashLookup.startSearch(hash2);
                for (int pos; (pos = hashLookup.nextPos()) >= 0; ) {
                    long offset = offsetFromPos(pos);
                    NativeBytes entry = entry(offset);
                    if (!keyEquals(keyBytes, keyLen, entry))
                        continue;
                    // key is found
                    entry.skip(keyLen);
                    return onKeyPresentOnReplace(key, expectedValue, newValue, pos, offset, entry,
                            hashLookup);
                }
                // key is not found
                return null;
            } finally {
                unlock();
            }
        }

        V onKeyPresentOnReplace(K key, V expectedValue, V newValue, int pos, long offset,
                                NativeBytes entry, IntIntMultiMap searchedHashLookup) {
            long valueLenPos = entry.position();
            long valueLen = readValueLen(entry);
            long entryEndAddr = entry.positionAddr() + valueLen;
            V valueRead = readValue(entry, null, valueLen);
            if (valueRead == null)
                return null;
            if (expectedValue == null || expectedValue.equals(valueRead)) {
                // putValue may relocate entry and change offset
                offset = putValue(pos, offset, entry, valueLenPos, entryEndAddr, newValue,
                        searchedHashLookup);
                notifyPut(offset, false, key, newValue,
                        posFromOffset(offset));
                return valueRead;
            }
            return null;
        }


        void notifyPut(long offset, boolean added, K key, V value, final long pos) {
            if (eventListener() != MapEventListeners.NOP) {
                tmpBytes.storePositionAndSize(bytes, offset, entrySize);
                eventListener().onPut(VanillaChronicleMap.this, tmpBytes, metaDataBytes,
                        added, key, value, pos, this);
            }
        }

        void notifyGet(long offset, K key, V value) {
            if (eventListener() != MapEventListeners.NOP) {
                tmpBytes.storePositionAndSize(bytes, offset, entrySize);
                eventListener().onGetFound(VanillaChronicleMap.this, tmpBytes, metaDataBytes,
                        key, value);
            }
        }

        V notifyMissed(Bytes keyBytes, K key, V usingValue) {
            if (eventListener() != MapEventListeners.NOP) {
                return eventListener().onGetMissing(VanillaChronicleMap.this, keyBytes,
                        key, usingValue);
            }
            return null;
        }

        void notifyRemoved(long offset, K key, V value, final int pos) {
            if (eventListener() != MapEventListeners.NOP) {
                tmpBytes.storePositionAndSize(bytes, offset, entrySize);
                eventListener().onRemove(VanillaChronicleMap.this, tmpBytes, metaDataBytes,
                        key, value, pos, this);
            }
        }

        long putValue(int pos, long offset, NativeBytes entry, long valueLenPos,
                      long entryEndAddr, V value, IntIntMultiMap searchedHashLookup) {
            if (value instanceof Byteable) {
                return putValue(pos, offset, entry, valueLenPos, entryEndAddr,
                        null, (Byteable) value, false, searchedHashLookup);
            } else {
                return putValue(pos, offset, entry, valueLenPos, entryEndAddr,
                        getValueAsBytes(value), null, true, searchedHashLookup);
            }
        }

        /**
         * Replaces value in existing entry. May cause entry relocation, because there may be not enough space
         * for new value in location already allocated for this entry.
         *
         * @param pos             index of the first block occupied by the entry
         * @param offset          relative offset of the entry in Segment bytes (before, i. e. including
         *                        metaData)
         * @param entry           relative pointer in Segment bytes
         * @param valueLenPos     relative position of value "stop bit" in entry
         * @param entryEndAddr    absolute address of the entry end
         * @param valueBytes      serialized value, or {@code null} if valueAsByteable is given
         * @param valueAsByteable the value to put as {@code Byteable}, or {@code null} if valueBytes is
         *                        given
         * @param allowOversize   {@code true} if the entry is allowed become oversized if it was not yet
         * @return relative offset of the entry in Segment bytes after putting value (that may cause entry
         * relocation)
         */
        long putValue(int pos, long offset, NativeBytes entry, long valueLenPos,
                      long entryEndAddr, @Nullable Bytes valueBytes,
                      @Nullable Byteable valueAsByteable, boolean allowOversize,
                      IntIntMultiMap searchedHashLookup) {
            long valueLenAddr = entry.address() + valueLenPos;
            long newValueLen;
            if (valueBytes != null) {
                newValueLen = valueBytes.remaining();
            } else {
                assert valueAsByteable != null;
                newValueLen = valueAsByteable.maxSize();
            }
            long newValueAddr = alignment.alignAddr(
                    valueLenAddr + expectedStopBits(newValueLen));
            long newEntryEndAddr = newValueAddr + newValueLen;
            // Fast check before counting "sizes in blocks" that include
            // integral division
            newValueDoesNotFit:
            if (newEntryEndAddr != entryEndAddr) {
                long entryStartAddr = entryStartAddr(offset);
                long oldEntrySize = entryEndAddr - entryStartAddr;
                int oldSizeInBlocks = inBlocks(oldEntrySize);
                int newSizeInBlocks = inBlocks(newEntryEndAddr - entryStartAddr);
                if (newSizeInBlocks > oldSizeInBlocks) {
                    if (!allowOversize && oldSizeInBlocks == 1) {
                        // If the value is Byteable, illegal oversize could be detected because of
                        // too high (inaccurate maxSize() estimate. Marshall the value to get
                        // the precise size.
                        if (valueAsByteable != null) {
                            // noinspection unchecked
                            return putValue(pos, offset, entry, valueLenPos, entryEndAddr,
                                    getValueAsBytes((V) valueAsByteable), null, false, searchedHashLookup);
                        }
                        throw new IllegalArgumentException("Byteable value is not allowed " +
                                "to make the entry oversized while it was not initially.");
                    }
                    if (newSizeInBlocks > MAX_ENTRY_OVERSIZE_FACTOR) {
                        if (valueAsByteable != null) {
                            // noinspection unchecked
                            return putValue(pos, offset, entry, valueLenPos, entryEndAddr,
                                    getValueAsBytes((V) valueAsByteable), null, false, searchedHashLookup);
                        }
                        throw new IllegalArgumentException("Value too large: " +
                                "entry takes " + newSizeInBlocks + " blocks, " +
                                MAX_ENTRY_OVERSIZE_FACTOR + " is maximum.");
                    }
                    if (realloc(pos, oldSizeInBlocks, newSizeInBlocks))
                        break newValueDoesNotFit;
                    // RELOCATION
                    free(pos, oldSizeInBlocks);
                    eventListener().onRelocation(pos, this);
                    int prevPos = pos;
                    pos = alloc(newSizeInBlocks);
                    // putValue() is called from put() and replace()
                    // after successful search by key
                    replacePosInHashLookupOnRelocation(searchedHashLookup, prevPos, pos);
                    offset = offsetFromPos(pos);
                    // Moving metadata, key stop bit and key.
                    // Don't want to fiddle with pseudo-buffers for this,
                    // since we already have all absolute addresses.
                    long newEntryStartAddr = entryStartAddr(offset);
                    NativeBytes.UNSAFE.copyMemory(entryStartAddr,
                            newEntryStartAddr, valueLenAddr - entryStartAddr);
                    entry = entry(offset);
                    // END OF RELOCATION
                } else if (newSizeInBlocks < oldSizeInBlocks) {
                    // Freeing extra blocks
                    freeList.clear(pos + newSizeInBlocks, pos + oldSizeInBlocks);
                    // Do NOT reset nextPosToSearchFrom, because if value
                    // once was larger it could easily became oversized again,
                    // But if these blocks will be taken by that time,
                    // this entry will need to be relocated.
                }
            }
            // Common code for all cases
            entry.position(valueLenPos);
            entry.writeStopBit(newValueLen);
            alignment.alignPositionAddr(entry);
            if (valueBytes != null) {
                entry.write(valueBytes);
            } else {
                if (valueAsByteable instanceof BytesMarshallable) {
                    long posAddr = entry.positionAddr();
                    ((BytesMarshallable) valueAsByteable).writeMarshallable(entry);
                    long actualValueLen = entry.positionAddr() - posAddr;
                    if (actualValueLen > newValueLen) {
                        throw new AssertionError(
                                "Byteable value returned maxSize less than the actual size");
                    }
                } else {
                    entry.write(valueAsByteable.bytes(), valueAsByteable.offset(), newValueLen);
                }
            }
            return offset;
        }

        void replacePosInHashLookupOnRelocation(IntIntMultiMap searchedHashLookup, int prevPos, int pos) {
            searchedHashLookup.replacePrevPos(pos);
        }

        void clear() {
            lock();
            try {
                hashLookup.clear();
                freeList.clear();
                resetSize();
            } finally {
                unlock();
            }
        }

        public Entry<K, V> getEntry(long pos) {
            bytes.position(offsetFromPos(pos) + metaDataBytes);
            bytes.readStopBit();
            K key = keyMarshaller.read(bytes);
            bytes.readStopBit();
            alignment.alignPositionAddr(bytes);
            V value = valueMarshaller.read(bytes);
            return new WriteThroughEntry(key, value);
        }

        /**
         * Check there is no garbage in freeList.
         */
        void checkConsistency() {
            lock();
            try {
                IntIntMultiMap hashLookup = checkConsistencyHashLookup();
                for (int pos = 0; (pos = (int) freeList.nextSetBit(pos)) >= 0; ) {
                    PosPresentOnce check = new PosPresentOnce(pos);
                    hashLookup.forEach(check);
                    if (check.count != 1)
                        throw new AssertionError();
                    long offset = offsetFromPos(pos);
                    Bytes entry = entry(offset);
                    long keyLen = entry.readStopBit();
                    entry.skip(keyLen);
                    afterKeyHookOnCheckConsistency(entry);
                    long valueLen = entry.readStopBit();
                    long sizeInBytes = entrySize(keyLen, valueLen);
                    int entrySizeInBlocks = inBlocks(sizeInBytes);
                    if (!freeList.allSet(pos, pos + entrySizeInBlocks))
                        throw new AssertionError();
                    pos += entrySizeInBlocks;
                }
            } finally {
                unlock();
            }
        }

        void afterKeyHookOnCheckConsistency(Bytes entry) {
            // no-op
        }

        IntIntMultiMap checkConsistencyHashLookup() {
            return hashLookup;
        }

        private class PosPresentOnce implements IntIntMultiMap.EntryConsumer {
            int pos, count = 0;

            PosPresentOnce(int pos) {
                this.pos = pos;
            }

            @Override
            public void accept(int hash, int pos) {
                if (this.pos == pos) count++;
            }
        }
    }

    class EntryIterator implements Iterator<Entry<K, V>> {
        Entry<K, V> returnedEntry;
        private int returnedSeg = -1;
        private long returnedPos = -1L;
        private int nextSeg;
        private long nextPos;

        public EntryIterator() {
            advance(nextSeg = segments.length - 1, nextPos = -1L);
        }

        private boolean advance(int segIndex, long pos) {
            while (segIndex >= 0) {
                pos = segments[segIndex].getHashLookup().getPositions().nextSetBit(pos + 1L);
                if (pos >= 0L) {
                    nextSeg = segIndex;
                    nextPos = pos;
                    return true;
                } else {
                    segIndex--;
                    pos = -1L;
                }
            }
            nextSeg = -1;
            nextPos = -1L;
            return false;
        }

        @Override
        public boolean hasNext() {
            return nextSeg >= 0;
        }

        @Override
        public Entry<K, V> next() {
            for (; ; ) {
                int segIndex = nextSeg;
                long pos = nextPos;
                if (segIndex < 0)
                    throw new NoSuchElementException();
                final Segment segment = segments[segIndex];
                try {
                    segment.lock();
                    if (segment.getHashLookup().getPositions().isClear(pos)) {
                        // the pos was removed after the previous advance
                        advance(segIndex, pos);
                        continue;
                    }
                    advance(returnedSeg = segIndex, returnedPos = pos);
                    return returnedEntry = segment.getEntry(pos);
                } finally {
                    segment.unlock();
                }
            }
        }

        @Override
        public void remove() {
            int segIndex = returnedSeg;
            if (segIndex < 0)
                throw new IllegalStateException();
            final Segment segment = segments[segIndex];
            final int pos = (int) returnedPos;
            try {
                segment.lock();
                if (segment.getHashLookup().getPositions().isClear(pos)) {
                    // The case:
                    // 1. iterator.next() - thread 1
                    // 2. map.put() which cause relocation of the key, returned in above - thread 2
                    // OR map.remove() which remove this key - thread 2
                    // 3. iterator.remove() - thread 1
                    VanillaChronicleMap.this.remove(returnedEntry.getKey());
                } else {
                    removePresent(segment, pos);
                }
                returnedSeg = -1;
                returnedEntry = null;
            } finally {
                segment.unlock();
            }
        }

        void removePresent(Segment segment, int pos) {
            // TODO handle the case:
            // iterator.next() -- thread 1
            // map.put() which cause relocation of the key, returned above -- thread 2
            // map.put() which place a new key on the `pos` in current segment -- thread 3
            // iterator.remove() -- thread 1
            // The simple solution is to compare bytes in the map with the serialized bytes
            // of returnedEntry.getKey(), but it seems rather wasteful to workaround so rare
            // case.
            final long offset = segment.offsetFromPos(pos);
            final NativeBytes entry = segment.entry(offset);

            final long limit = entry.limit();
            final long keyLen = entry.readStopBit();
            entry.limit(entry.position() + keyLen);
            final int segmentHash = hasher.segmentHash(hash(entry));
            entry.limit(limit);

            entry.skip(keyLen);
            long valueLen = segment.readValueLen(entry);
            final long entryEndAddr = entry.positionAddr() + valueLen;
            segment.getHashLookup().remove(segmentHash, pos);
            segment.decrementSize();
            segment.free(pos, segment.inBlocks(entryEndAddr - segment.entryStartAddr(offset)));
            segment.notifyRemoved(offset, returnedEntry.getKey(), returnedEntry.getValue(), pos);
        }
    }

    class EntrySet extends AbstractSet<Map.Entry<K, V>> {
        @NotNull
        public Iterator<Map.Entry<K, V>> iterator() {
            return new EntryIterator();
        }

        public boolean contains(Object o) {
            if (!(o instanceof Map.Entry))
                return false;
            Map.Entry<?, ?> e = (Map.Entry<?, ?>) o;
            try {
                V v = VanillaChronicleMap.this.get(e.getKey());
                return v != null && v.equals(e.getValue());
            } catch (ClassCastException ex) {
                return false;
            } catch (NullPointerException ex) {
                return false;
            }
        }

        public boolean remove(Object o) {
            if (!(o instanceof Map.Entry))
                return false;
            Map.Entry<?, ?> e = (Map.Entry<?, ?>) o;
            try {
                Object key = e.getKey();
                Object value = e.getValue();
                return VanillaChronicleMap.this.remove(key, value);
            } catch (ClassCastException ex) {
                return false;
            } catch (NullPointerException ex) {
                return false;
            }
        }

        public int size() {
            return VanillaChronicleMap.this.size();
        }

        public boolean isEmpty() {
            return VanillaChronicleMap.this.isEmpty();
        }

        public void clear() {
            VanillaChronicleMap.this.clear();
        }
    }

    final class WriteThroughEntry extends SimpleEntry<K, V> {

        WriteThroughEntry(K key, V value) {
            super(key, value);
        }

        @Override
        public V setValue(V value) {
            put(getKey(), value);
            return super.setValue(value);
        }
    }
}
