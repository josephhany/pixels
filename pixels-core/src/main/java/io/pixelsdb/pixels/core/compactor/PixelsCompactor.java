/*
 * Copyright 2017-2019 PixelsDB.
 *
 * This file is part of Pixels.
 *
 * Pixels is free software: you can redistribute it and/or modify
 * it under the terms of the Affero GNU General Public License as
 * published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * Pixels is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * Affero GNU General Public License for more details.
 *
 * You should have received a copy of the Affero GNU General Public
 * License along with Pixels.  If not, see
 * <https://www.gnu.org/licenses/>.
 */
package io.pixelsdb.pixels.core.compactor;

import com.google.common.collect.ImmutableList;
import io.pixelsdb.pixels.common.physical.*;
import io.pixelsdb.pixels.common.utils.Constants;
import io.pixelsdb.pixels.core.PixelsProto;
import io.pixelsdb.pixels.core.PixelsVersion;
import io.pixelsdb.pixels.core.TypeDescription;
import io.pixelsdb.pixels.core.exception.PixelsFileMagicInvalidException;
import io.pixelsdb.pixels.core.exception.PixelsFileVersionInvalidException;
import io.pixelsdb.pixels.core.stats.StatsRecorder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.List;
import java.util.TimeZone;

import static com.google.common.base.Preconditions.checkArgument;
import static io.pixelsdb.pixels.common.utils.Constants.DEFAULT_HDFS_BLOCK_SIZE;
import static java.util.Objects.requireNonNull;

/**
 * Pixels file compactor
 *
 * @author hank
 */
public class PixelsCompactor
{
    private static final Logger LOGGER = LogManager.getLogger(PixelsCompactor.class);

    private final TypeDescription schema;
    private final CompactLayout compactLayout;
    private final int pixelStride;
    private final PixelsProto.CompressionKind compressionKind;
    private final int compressionBlockSize;
    private final TimeZone timeZone;
    private final long fileContentLength;
    private final int fileRowNum;

    private final Storage storage;
    private final PhysicalWriter fsWriter;
    private final StatsRecorder[] fileColStatRecorders;

    private final List<PixelsProto.RowGroupInformation.Builder> rowGroupInfoBuilderList;    // row group information in footer
    private final List<PixelsProto.RowGroupStatistic.Builder> rowGroupStatBuilderList; // row group statistic in footer
    private final List<PixelsProto.RowGroupFooter.Builder> rowGroupFooterBuilderList; // row group fotters
    private final List<String> rowGroupPaths;

    private PixelsCompactor(
            TypeDescription schema,
            CompactLayout compactLayout,
            int pixelStride,
            PixelsProto.CompressionKind compressionKind,
            int compressionBlockSize,
            TimeZone timeZone,
            long fileContentLength,
            int fileRowNum,
            Storage storage,
            PhysicalWriter fsWriter,
            StatsRecorder[] fileColStatRecorders,
            List<PixelsProto.RowGroupInformation.Builder> rowGroupInfoBuilderList,
            List<PixelsProto.RowGroupStatistic.Builder> rowGroupStatBuilderList,
            List<PixelsProto.RowGroupFooter.Builder> rowGroupFooterBuilderList,
            List<String> rowGroupPaths)
    {
        this.schema = requireNonNull(schema, "schema is null");
        this.compactLayout = requireNonNull(compactLayout, "compactLayout is null");
        checkArgument(pixelStride > 0, "pixel stripe is not positive");
        this.pixelStride = pixelStride;
        this.compressionKind = requireNonNull(compressionKind);
        checkArgument(compressionBlockSize > 0, "compression block size is not positive");
        this.compressionBlockSize = compressionBlockSize;
        this.timeZone = requireNonNull(timeZone);
        checkArgument(fileContentLength > 0, "file content length is not positive");
        this.fileContentLength = fileContentLength;
        checkArgument(fileRowNum > 0, "file row number is not positive");
        this.fileRowNum = fileRowNum;

        this.storage = requireNonNull(storage, "storage is null");
        this.fsWriter = requireNonNull(fsWriter, "fsWriter is null");

        this.fileColStatRecorders = requireNonNull(fileColStatRecorders, "file column stat reader is null");

        checkArgument(!requireNonNull(rowGroupFooterBuilderList).isEmpty());
        checkArgument(!requireNonNull(rowGroupStatBuilderList).isEmpty());
        checkArgument(!requireNonNull(rowGroupFooterBuilderList).isEmpty());
        checkArgument(!requireNonNull(rowGroupPaths).isEmpty());
        this.rowGroupInfoBuilderList = ImmutableList.copyOf(rowGroupInfoBuilderList);
        this.rowGroupStatBuilderList = ImmutableList.copyOf(rowGroupStatBuilderList);
        this.rowGroupFooterBuilderList = ImmutableList.copyOf(rowGroupFooterBuilderList);
        this.rowGroupPaths = ImmutableList.copyOf(rowGroupPaths);
    }

    public static class Builder
    {
        private TypeDescription schema = null;
        private List<String> sourcePaths = null;
        private CompactLayout compactLayout = null;
        private TimeZone builderTimeZone = TimeZone.getDefault();
        private Storage builderStorage = null;
        private String builderFilePath = null;
        private StatsRecorder[] fileColStatRecorders;
        private long builderBlockSize = DEFAULT_HDFS_BLOCK_SIZE;
        private short builderReplication = 3;
        private boolean builderBlockPadding = true;
        private boolean builderOverwrite = false;
        private PixelsProto.CompressionKind compressionKind = null;
        private int compressionBlockSize = 1;
        private int pixelStride = 0;
        private long fileContentLength = 0L;
        private int fileRowNum = 0;
        private PhysicalWriter fsWriter = null;
        private List<PixelsProto.RowGroupInformation.Builder> rowGroupInfoBuilderList = new LinkedList<>();
        private List<PixelsProto.RowGroupStatistic.Builder> rowGroupStatBuilderList = new LinkedList<>();
        private List<PixelsProto.RowGroupFooter.Builder> rowGroupFooterBuilderList = new LinkedList<>();
        private List<String> rowGroupPaths = new LinkedList<>();

        private Builder()
        {
        }

        /**
         * set schema is optional, if schema is not set, the schema read from the first source file will be used as this.schema.
         * and this.schema will be used as the schema of the compacted file.
         *
         * @param schema
         * @return
         */
        public PixelsCompactor.Builder setSchema(TypeDescription schema)
        {
            this.schema = schema;

            return this;
        }

        public PixelsCompactor.Builder setSourcePaths(List<String> sourcePaths)
        {
            this.sourcePaths = ImmutableList.copyOf(requireNonNull(sourcePaths));

            return this;
        }

        public PixelsCompactor.Builder setCompactLayout(CompactLayout compactLayout)
        {
            this.compactLayout = requireNonNull(compactLayout);

            return this;
        }

        public PixelsCompactor.Builder setStorage(Storage storage)
        {
            this.builderStorage = requireNonNull(storage);

            return this;
        }

        public PixelsCompactor.Builder setPath(String filePath)
        {
            this.builderFilePath = requireNonNull(filePath);

            return this;
        }

        public PixelsCompactor.Builder setTimeZone(TimeZone timeZone)
        {
            this.builderTimeZone = requireNonNull(timeZone);

            return this;
        }

        public PixelsCompactor.Builder setBlockSize(long blockSize)
        {
            this.builderBlockSize = blockSize;

            return this;
        }

        public PixelsCompactor.Builder setReplication(short replication)
        {
            this.builderReplication = replication;

            return this;
        }

        public PixelsCompactor.Builder setBlockPadding(boolean blockPadding)
        {
            this.builderBlockPadding = blockPadding;

            return this;
        }

        public Builder setOverwrite(boolean overwrite)
        {
            this.builderOverwrite = overwrite;

            return this;
        }

        public PixelsCompactor build()
                throws IOException
        {
            // check arguments
            if (sourcePaths == null || compactLayout == null || builderStorage == null ||
                    builderFilePath == null || pixelStride <= 0)
            {
                throw new IllegalArgumentException("Missing argument to build PixelsCompactor");
            }

            // read each source file footer
            for (int i = 0; i < sourcePaths.size(); i++)
            {
                String path = sourcePaths.get(i);
                PhysicalReader fsReader = PhysicalReaderUtil.newPhysicalReader(builderStorage, path);
                if (fsReader == null)
                {
                    throw new IOException("Read file failed.");
                }

                // get FileTail
                long fileLen = fsReader.getFileLength();
                fsReader.seek(fileLen - Long.BYTES);
                long fileTailOffset = fsReader.readLong();
                int fileTailLength = (int) (fileLen - fileTailOffset - Long.BYTES);
                fsReader.seek(fileTailOffset);
                byte[] fileTailBuffer = new byte[fileTailLength];
                fsReader.readFully(fileTailBuffer);
                PixelsProto.FileTail fileTail = PixelsProto.FileTail.parseFrom(fileTailBuffer);

                if (fileTail == null)
                {
                    fsReader.close();
                    throw new IOException("read file tail failed.");
                }

                // check file MAGIC and file version
                PixelsProto.PostScript postScript = fileTail.getPostscript();
                int fileVersion = postScript.getVersion();
                String fileMagic = postScript.getMagic();
                if (!PixelsVersion.matchVersion(fileVersion))
                {
                    fsReader.close();
                    throw new PixelsFileVersionInvalidException(fileVersion);
                }
                if (!fileMagic.contentEquals(Constants.MAGIC))
                {
                    fsReader.close();
                    throw new PixelsFileMagicInvalidException(fileMagic);
                }

                if (i == 0)
                {
                    compressionKind = fileTail.getPostscript().getCompression();
                    compressionBlockSize = fileTail.getPostscript().getCompressionBlockSize();
                    pixelStride = fileTail.getPostscript().getPixelStride();

                    if (schema == null)
                    {
                        schema = TypeDescription.createSchema(fileTail.getFooter().getTypesList());
                    }

                    List<TypeDescription> childrenSchema = schema.getChildren();
                    checkArgument(!requireNonNull(childrenSchema).isEmpty());
                    fileColStatRecorders = new StatsRecorder[childrenSchema.size()];
                    for (int j = 0; j < childrenSchema.size(); ++j)
                    {
                        this.fileColStatRecorders[j] = StatsRecorder
                                .create(childrenSchema.get(j)); // to be updated when compacting
                    }
                }

                fileContentLength += postScript.getContentLength(); // init fileContentLength
                fileRowNum += postScript.getNumberOfRows(); // init fileRowNum

                PixelsProto.Footer footer = fileTail.getFooter();
                // init rowGroupStatisticList
                for (PixelsProto.RowGroupStatistic stat : footer.getRowGroupStatsList())
                {
                    rowGroupStatBuilderList.add(stat.toBuilder());
                }
                for (PixelsProto.RowGroupInformation info : footer.getRowGroupInfosList())
                {
                    rowGroupInfoBuilderList.add(info.toBuilder()); // footerOffset to be updated when compacting
                    long footerOffset = info.getFooterOffset();
                    long footerLength = info.getFooterLength();
                    fsReader.seek(footerOffset);
                    byte[] footerBuffer = new byte[(int) footerLength];
                    fsReader.readFully(footerBuffer);
                    PixelsProto.RowGroupFooter rowGroupFooter =
                            PixelsProto.RowGroupFooter.parseFrom(footerBuffer);
                    rowGroupFooterBuilderList
                            .add(rowGroupFooter.toBuilder()); // chunkOffset to be updated when compacting
                    rowGroupPaths.add(path);
                }
                fsReader.close();
            }

            fsWriter = PhysicalWriterUtil.newPhysicalWriter(builderStorage, builderFilePath, builderBlockSize,
                    builderReplication, builderBlockPadding, builderOverwrite);

            return new PixelsCompactor(
                    schema,
                    compactLayout,
                    pixelStride,
                    compressionKind,
                    compressionBlockSize,
                    builderTimeZone,
                    fileContentLength,
                    fileRowNum,
                    builderStorage,
                    fsWriter,
                    fileColStatRecorders,
                    rowGroupInfoBuilderList,
                    rowGroupStatBuilderList,
                    rowGroupFooterBuilderList,
                    rowGroupPaths);
        }
    }

    public static PixelsCompactor.Builder newBuilder()
    {
        return new PixelsCompactor.Builder();
    }

    public void compact()
    {
        this.writeColumnChunks();
        this.writeRowGroupFooters();
        this.writeFileTail();
    }

    private void writeColumnChunks()
    {
        for (int i = 0; i < this.compactLayout.size(); ++i)
        {
            ColumnletIndex index = this.compactLayout.get(i);
            int rowGroupId = index.getRowGroupId();
            int columnId = index.getColumnId();
            PixelsProto.ColumnChunkIndex.Builder columnChunkIndexBuilder =
                    this.rowGroupFooterBuilderList.get(rowGroupId).getRowGroupIndexEntryBuilder()
                            .getColumnChunkIndexEntriesBuilder(columnId);
            long columnChunkOffset = columnChunkIndexBuilder.getChunkOffset();
            long columnChunkLength = columnChunkIndexBuilder.getChunkLength();
            String path = this.rowGroupPaths.get(rowGroupId);
            try (PhysicalReader fsReader = PhysicalReaderUtil.newPhysicalReader(storage, path))
            {
                if (fsReader == null)
                {
                    throw new IOException("read file failed.");
                }
                fsReader.seek(columnChunkOffset);
                byte[] chunkBuffer = new byte[(int) columnChunkLength];
                fsReader.readFully(chunkBuffer);
                fsWriter.prepare((int) columnChunkLength);
                long offset = this.fsWriter.append(chunkBuffer, 0, (int) columnChunkLength);
                columnChunkIndexBuilder.setChunkOffset(offset);
                // this.fsWriter.flush(); // Issue #192: no need to flush as writing has not finished.
            }
            catch (IOException e)
            {
                LOGGER.error(e.getMessage());
                e.printStackTrace();
            }
        }
    }

    private void writeRowGroupFooters()
    {
        for (int i = 0; i < this.rowGroupFooterBuilderList.size(); ++i)
        {
            PixelsProto.RowGroupFooter rowGroupFooter = this.rowGroupFooterBuilderList.get(i).build();
            ByteBuffer rowGroupFooterBuffer = ByteBuffer.allocate(rowGroupFooter.getSerializedSize());
            rowGroupFooterBuffer.put(rowGroupFooter.toByteArray());
            try
            {
                fsWriter.prepare(rowGroupFooter.getSerializedSize());
                long rowGroupFooterOffset = fsWriter.append(rowGroupFooterBuffer);
                // fsWriter.flush(); // Issue #192: no need to flush as writing has not finished.
                this.rowGroupInfoBuilderList.get(i).setFooterOffset(rowGroupFooterOffset);
                this.rowGroupInfoBuilderList.get(i).setFooterLength(rowGroupFooter.getSerializedSize());
            }
            catch (IOException e)
            {
                LOGGER.error(e.getMessage());
                e.printStackTrace();
                return;
            }

            List<PixelsProto.ColumnStatistic> columnChunkStats =
                    this.rowGroupStatBuilderList.get(i).getColumnChunkStatsList();
            List<TypeDescription> children = this.schema.getChildren();
            checkArgument(!requireNonNull(children).isEmpty());
            for (int j = 0; j < children.size(); ++j)
            {
                fileColStatRecorders[j].merge(StatsRecorder.create(children.get(j), columnChunkStats.get(j)));
            }
        }
    }

    private void writeFileTail()
    {
        PixelsProto.Footer footer = writeFooter();
        PixelsProto.PostScript postScript = writePostScript();

        PixelsProto.FileTail fileTail =
                PixelsProto.FileTail.newBuilder()
                        .setFooter(footer)
                        .setPostscript(postScript)
                        .setFooterLength(footer.getSerializedSize())
                        .setPostscriptLength(postScript.getSerializedSize())
                        .build();

        try
        {
            // write and flush FileTail plus FileTail physical offset at the end of the file
            int fileTailLen = fileTail.getSerializedSize() + Long.BYTES;
            fsWriter.prepare(fileTailLen);
            long tailOffset = fsWriter.append(fileTail.toByteArray(), 0, fileTail.getSerializedSize());
            ByteBuffer tailOffsetBuffer = ByteBuffer.allocate(Long.BYTES);
            tailOffsetBuffer.putLong(tailOffset);
            fsWriter.append(tailOffsetBuffer);
            fsWriter.flush();
        }
        catch (IOException e)
        {
            LOGGER.error(e.getMessage());
            System.out.println("Error writing file tail out.");
            e.printStackTrace();
        }
    }

    private PixelsProto.Footer writeFooter()
    {
        PixelsProto.Footer.Builder footerBuilder =
                PixelsProto.Footer.newBuilder();
        TypeDescription.writeTypes(footerBuilder, schema);
        for (StatsRecorder recorder : fileColStatRecorders)
        {
            footerBuilder.addColumnStats(recorder.serialize().build());
        }
        for (PixelsProto.RowGroupInformation.Builder rowGroupInformationBuilder : rowGroupInfoBuilderList)
        {
            footerBuilder.addRowGroupInfos(rowGroupInformationBuilder.build());
        }
        for (PixelsProto.RowGroupStatistic.Builder rowGroupStatisticBuilder : rowGroupStatBuilderList)
        {
            footerBuilder.addRowGroupStats(rowGroupStatisticBuilder.build());
        }

        return footerBuilder.build();
    }

    private PixelsProto.PostScript writePostScript()
    {
        return PixelsProto.PostScript.newBuilder()
                .setVersion(Constants.VERSION)
                .setContentLength(fileContentLength)
                .setNumberOfRows(fileRowNum)
                .setCompression(compressionKind)
                .setCompressionBlockSize(compressionBlockSize)
                .setPixelStride(pixelStride)
                .setWriterTimezone(timeZone.getDisplayName())
                .setMagic(Constants.MAGIC)
                .build();
    }

    /**
     * Close PixelsCompactor, indicating the end of file
     */
    public void close()
    {
        try
        {
            fsWriter.close();
        }
        catch (IOException e)
        {
            LOGGER.error(e.getMessage());
            System.out.println("Error writing file tail out.");
            e.printStackTrace();
        }
    }
}
