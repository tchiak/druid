/*
 * Druid - a distributed column store.
 * Copyright 2012 - 2015 Metamarkets Group Inc.
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

package io.druid.storage.azure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.google.inject.Inject;
import com.metamx.common.CompressionUtils;
import com.metamx.common.logger.Logger;
import com.microsoft.azure.storage.StorageException;
import io.druid.segment.SegmentUtils;
import io.druid.segment.loading.DataSegmentPusher;
import io.druid.segment.loading.DataSegmentPusherUtil;
import io.druid.timeline.DataSegment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.concurrent.Callable;

public class AzureDataSegmentPusher implements DataSegmentPusher
{

  private static final Logger log = new Logger(AzureDataSegmentPusher.class);
  private final AzureStorage azureStorage;
  private final AzureAccountConfig config;
  private final ObjectMapper jsonMapper;

  @Inject
  public AzureDataSegmentPusher(
      AzureStorage azureStorage,
      AzureAccountConfig config,
      ObjectMapper jsonMapper
  )
  {
    this.azureStorage = azureStorage;
    this.config = config;
    this.jsonMapper = jsonMapper;
  }

  @Override
  public String getPathForHadoop(String dataSource)
  {
    return null;
  }

  public File createCompressedSegmentDataFile(final File indexFilesDir) throws IOException
  {
    final File zipOutFile = File.createTempFile("index", ".zip");
    CompressionUtils.zip(indexFilesDir, zipOutFile);

    return zipOutFile;

  }

  public File createSegmentDescriptorFile(final ObjectMapper jsonMapper, final DataSegment segment) throws
                                                                                                    IOException
  {
    File descriptorFile = File.createTempFile("descriptor", ".json");
    try (FileOutputStream stream = new FileOutputStream(descriptorFile)) {
      stream.write(jsonMapper.writeValueAsBytes(segment));
    }

    return descriptorFile;
  }

  public Map<String, String> getAzurePaths(final DataSegment segment)
  {
    final String storageDir = DataSegmentPusherUtil.getStorageDir(segment);

    return ImmutableMap.of(
        "index", String.format("%s/%s", storageDir, AzureStorageDruidModule.INDEX_ZIP_FILE_NAME),
        "descriptor", String.format("%s/%s", storageDir, AzureStorageDruidModule.DESCRIPTOR_FILE_NAME)
    );

  }

  public DataSegment uploadDataSegment(
      DataSegment segment,
      final int version,
      final File compressedSegmentData,
      final File descriptorFile,
      final Map<String, String> azurePaths
  )
      throws StorageException, IOException, URISyntaxException
  {
    azureStorage.uploadBlob(compressedSegmentData, config.getContainer(), azurePaths.get("index"));
    azureStorage.uploadBlob(descriptorFile, config.getContainer(), azurePaths.get("descriptor"));

    final DataSegment outSegment = segment
        .withSize(compressedSegmentData.length())
        .withLoadSpec(
            ImmutableMap.<String, Object>of(
                "type",
                AzureStorageDruidModule.SCHEME,
                "containerName",
                config.getContainer(),
                "blobPath",
                azurePaths.get("index")
            )
        )
        .withBinaryVersion(version);

    log.info("Deleting file [%s]", compressedSegmentData);
    compressedSegmentData.delete();

    log.info("Deleting file [%s]", descriptorFile);
    descriptorFile.delete();

    return outSegment;
  }

  @Override
  public DataSegment push(final File indexFilesDir, final DataSegment segment) throws IOException
  {

    log.info("Uploading [%s] to Azure.", indexFilesDir);

    final int version = SegmentUtils.getVersionFromDir(indexFilesDir);
    final File compressedSegmentData = createCompressedSegmentDataFile(indexFilesDir);
    final File descriptorFile = createSegmentDescriptorFile(jsonMapper, segment);
    final Map<String, String> azurePaths = getAzurePaths(segment);

    try {
      return AzureUtils.retryAzureOperation(
          new Callable<DataSegment>()
          {
            @Override
            public DataSegment call() throws Exception
            {
              return uploadDataSegment(segment, version, compressedSegmentData, descriptorFile, azurePaths);
            }
          },
          config.getMaxTries()
      );
    }
    catch (Exception e) {
      throw Throwables.propagate(e);
    }
  }
}
