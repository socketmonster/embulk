package org.embulk.spi;

import java.util.Map;
import java.util.List;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import io.airlift.slice.Slice;
import org.embulk.time.Timestamp;
import org.embulk.type.Schema;
import org.embulk.type.Column;

public class PageBuilder
        implements AutoCloseable
{
    private final BufferAllocator allocator;
    private final PageOutput output;
    private final int[] columnOffsets;
    private final int fixedRecordSize;

    private Buffer buffer;
    private Slice bufferSlice;

    private int count;
    private int position;
    private final byte[] nullBitSet;
    private final BiMap<String, Integer> stringReferences = HashBiMap.create();
    private int stringReferenceSize;
    private int nextVariableLengthDataOffset;

    public PageBuilder(BufferAllocator allocator, Schema schema, PageOutput output)
    {
        this.allocator = allocator;
        this.output = output;
        this.columnOffsets = Page.columnOffsets(schema);
        this.nullBitSet = new byte[Page.nullBitSetSize(schema)];
        this.fixedRecordSize = Page.recordHeaderSize(schema) + Page.totalColumnSize(schema);
        this.nextVariableLengthDataOffset = fixedRecordSize;
        newBuffer();
    }

    private void newBuffer()
    {
        this.buffer = allocator.allocate(Page.PAGE_HEADER_SIZE + fixedRecordSize);
        this.bufferSlice = Slices.wrappedBuffer(buffer.array(), buffer.offset(), buffer.limit());
        this.count = 0;
        this.position = Page.PAGE_HEADER_SIZE;
        this.stringReferences.clear();
        this.stringReferenceSize = 0;
    }

    public Schema getSchema()
    {
        return schema;
    }

    public void setNull(Column column)
    {
        setNull(column.getIndex());
    }

    public void setNull(int columnIndex)
    {
        nullBitSet[columnIndex >>> 3] |= (1 << (columnIndex & 7));
    }

    public void setBoolean(Column column, boolean value)
    {
        // TODO check type?
        setBoolean(column.getIndex(), value);
    }

    public void setBoolean(int columnIndex, boolean value)
    {
        bufferSlice.setByte(getOffset(columnIndex), value ? (byte) 1 : (byte) 0);
    }

    public void setLong(Column column, long value)
    {
        // TODO check type?
        setLong(column.getIndex(), value);
    }

    public void setLong(int columnIndex, long value)
    {
        bufferSlice.setLong(getOffset(columnIndex), value);
    }

    public void setDouble(Column column, double value)
    {
        // TODO check type?
        setDouble(column.getIndex(), value);
    }

    public void setDouble(int columnIndex, double value)
    {
        bufferSlice.setDouble(getOffset(columnIndex), value);
    }

    public void setString(Column column, String value)
    {
        // TODO check type?
        setString(column.getIndex(), value);
    }

    public void setString(int columnIndex, String value)
    {
        Integer reuseIndex = stringReferences.get(value);
        if (reuseIndex != null) {
            bufferSlice.setInt(getOffset(columnIndex), reuseIndex);
        } else {
            int index = stringReferences.size();
            stringReferences.put(value, index);
            bufferSlice.setInt(getOffset(columnIndex), index);
            stringReferenceSize += value.length() * 2 + 4;  // assuming size of char = size of byte * 2 + length
        }
    }

    public void setTimestamp(Column column, Timestamp value)
    {
        // TODO check type?
        return setTimestamp(column.getIndex(), value);
    }

    public void setTimestamp(int columnIndex, Timestamp value)
    {
        int offset = getOffset(columnIndex);
        bufferSlice.setLong(offset, value.getEpochSecond());
        bufferSlice.setInt(offset + 8, value.getNano());
    }

    private int getOffset(int columnIndex)
    {
        return position + columnOffsets[columnIndex];
    }

    private static class StringReferenceSortComparator
            implements Comparator<Map.Entry<String, Integer>>
    {
        @Override
        public int compare(Map.Entry<String, Integer> e1, Map.Entry<String, Integer> e2)
        {
            return e1.getValue().compareTo(e2.getValue());
        }

        @Override
        public boolean equals(Object obj)
        {
            return obj instanceof StringReferenceSortComparator;
        }
    }

    private List<String> getSortedStringReferences()
    {
        ArrayList<Map.Entry<String, Integer>> s = new ArrayList<>(stringReferences.entrySet());
        Collections.sort(s, new StringReferenceSortComparator());
        String[] array = new String[s.size()];
        for (int i=0; i < array.length; i++) {
            array[i] = s.get(i).getKey();
        }
        return Arrays.asList(array);
    }

    public void addRecord()
    {
        // record header
        bufferSlice.setInt(position, nextVariableLengthDataOffset);  // nextVariableLengthDataOffset means record size
        bufferSlice.setBytes(position + 4, nullBitSet);
        count++;

        this.position += nextVariableLengthDataOffset;
        this.nextVariableLengthDataOffset = fixedRecordSize;
        Arrays.fill(nullBitSet, (byte) 0);

        // flush if next record will not fit in this buffer
        if (buffer.capacity() < position + nextVariableLengthDataOffset + stringReferenceSize) {
            flush();
        }
    }

    public void flush()
    {
        finish();
        if (buffer == null) {
            newBuffer();
        }
    }

    public void finish()
    {
        // similar to flush but doesn't allocate next buffer
        if (buffer != null && count > 0) {
            // write page header
            bufferSlice.setInt(0, count);
            buffer.limit(position);

            // flush page
            output.add(Page.wrap(buffer).setStringReferences(getSortedStringReferences()));
            buffer = null;
            bufferSlice = null;
        }
    }

    @Override
    public void close()
    {
        if (buffer != null) {
            buffer.release();
            buffer = null;
            bufferSlice = null;
        }
    }

    /* TODO for variable-length types
    private void flushAndTakeOverRemaingData()
    {
        if (page != null) {
            // page header
            page.setInt(0, count);

            Page lastPage = page;

            this.page = allocator.allocatePage(Page.PAGE_HEADER_SIZE + fixedRecordSize + nextVariableLengthDataOffset);
            page.setBytes(Page.PAGE_HEADER_SIZE, lastPage, position, nextVariableLengthDataOffset);
            this.count = 0;
            this.position = Page.PAGE_HEADER_SIZE;

            output.add(lastPage);
        }
    }

    public int getVariableLengthDataOffset()
    {
        return nextVariableLengthDataOffset;
    }

    public VariableLengthDataWriter setVariableLengthData(int columnIndex, int intData)
    {
        // Page.VARIABLE_LENGTH_COLUMN_SIZE is 4 bytes
        page.setInt(position + columnOffsets[columnIndex], intData);
        return new VariableLengthDataWriter(nextVariableLengthDataOffset);
    }

    Page ensureVariableLengthDataCapacity(int requiredOffsetFromPosition)
    {
        if (page.capacity() < position + requiredOffsetFromPosition) {
            flushAndTakeOverRemaingData();
        }
        return page;
    }

    public class VariableLengthDataWriter
    {
        private int offsetFromPosition;

        VariableLengthDataWriter(int offsetFromPosition)
        {
            this.offsetFromPosition = offsetFromPosition;
        }

        public void writeByte(byte value)
        {
            ensureVariableLengthDataCapacity(offsetFromPosition + 1);
            page.setByte(position + offsetFromPosition, value);
            offsetFromPosition += 1;
        }

        public void writeShort(short value)
        {
            ensureVariableLengthDataCapacity(offsetFromPosition + 2);
            page.setShort(position + offsetFromPosition, value);
            offsetFromPosition += 2;
        }

        public void writeInt(int value)
        {
            ensureVariableLengthDataCapacity(offsetFromPosition + 4);
            page.setInt(position + offsetFromPosition, value);
            offsetFromPosition += 4;
        }

        public void writeLong(long value)
        {
            ensureVariableLengthDataCapacity(offsetFromPosition + 8);
            page.setLong(position + offsetFromPosition, value);
            offsetFromPosition += 8;
        }

        public void writeFloat(float value)
        {
            ensureVariableLengthDataCapacity(offsetFromPosition + 4);
            page.setFloat(position + offsetFromPosition, value);
            offsetFromPosition += 4;
        }

        public void writeDouble(double value)
        {
            ensureVariableLengthDataCapacity(offsetFromPosition + 8);
            page.setDouble(position + offsetFromPosition, value);
            offsetFromPosition += 8;
        }

        public void writeBytes(byte[] data)
        {
            writeBytes(data, 0, data.length);
        }

        public void writeBytes(byte[] data, int off, int len)
        {
            ensureVariableLengthDataCapacity(offsetFromPosition + len);
            page.setBytes(position + offsetFromPosition, data, off, len);
            offsetFromPosition += len;
        }
    }
    */
}