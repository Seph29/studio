/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui.service.mock;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import io.quarkus.arc.profile.IfBuildProfile;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.eventbus.EventBus;
import io.vertx.mutiny.ext.web.Router;
import studio.config.StudioConfig;
import studio.core.v1.model.metadata.StoryPackMetadata;
import studio.core.v1.reader.binary.BinaryStoryPackReader;
import studio.core.v1.utils.PackFormat;
import studio.core.v1.utils.exception.StoryTellerException;
import studio.driver.fs.FileUtils;
import studio.metadata.DatabaseMetadataService;
import studio.webui.model.DeviceDTOs.DeviceInfosDTO;
import studio.webui.model.DeviceDTOs.DeviceInfosDTO.StorageDTO;
import studio.webui.model.LibraryDTOs.MetaPackDTO;
import studio.webui.service.IStoryTellerService;

@IfBuildProfile("dev")
@ApplicationScoped
public class MockStoryTellerService implements IStoryTellerService {

    private static final Logger LOGGER = LogManager.getLogger(MockStoryTellerService.class);

    private static final int BUFFER_SIZE = 1024 * 1024 * 10;

    @Inject
    EventBus eventBus;

    @Inject
    DatabaseMetadataService databaseMetadataService;

    public void init(@Observes Router router) {
        LOGGER.info("Setting up mocked story teller service");
        try {
            // Create the mocked device folder if needed
            Files.createDirectories(devicePath());
        } catch (IOException e) {
            throw new StoryTellerException("Failed to initialize mocked device");
        }
        // plug event
        sendDevicePlugged(getDeviceInfo());
    }

    public CompletionStage<DeviceInfosDTO> deviceInfos() {
        return CompletableFuture.completedStage(getDeviceInfo());
    }

    public CompletionStage<List<MetaPackDTO>> packs() {
        // Check that mocked device folder exists
        Path deviceFolder = devicePath();
        if (!Files.isDirectory(deviceFolder)) {
            return CompletableFuture.completedStage(Arrays.asList());
        }
        return readPackIndex(deviceFolder).thenApply(p -> p.stream().map(this::toDto).collect(Collectors.toList()));
    }

    public CompletionStage<String> addPack(String uuid, Path packFile) {
        // Check that mocked device folder exists
        Path deviceFolder = devicePath();
        if (!Files.isDirectory(deviceFolder)) {
            return CompletableFuture.completedStage(uuid);
        }
        Path destFile = deviceFolder.resolve(uuid + ".pack");
        return copyPack("add pack", packFile, destFile);
    }

    public CompletionStage<String> extractPack(String uuid, Path destFile) {
        // Check that mocked device folder exists
        Path deviceFolder = devicePath();
        if (!Files.isDirectory(deviceFolder)) {
            return CompletableFuture.completedStage(uuid);
        }
        Path packFile = deviceFolder.resolve(uuid + ".pack");
        return copyPack("extract pack", packFile, destFile);
    }

    public CompletionStage<Boolean> deletePack(String uuid) {
        // Check that mocked device folder exists
        Path deviceFolder = devicePath();
        if (!Files.isDirectory(deviceFolder)) {
            return CompletableFuture.completedStage(false);
        }
        try {
            Path packFile = deviceFolder.resolve(uuid + ".pack");
            if (Files.deleteIfExists(packFile)) {
                return CompletableFuture.completedStage(true);
            } else {
                LOGGER.error("Cannot remove pack from mocked device because it is not in the folder");
                return CompletableFuture.completedStage(false);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to remove pack from mocked device", e);
            return CompletableFuture.completedStage(false);
        }
    }

    public CompletionStage<Boolean> reorderPacks(List<String> uuids) {
        // Not supported
        LOGGER.warn("Not supported : reorderPacks");
        return CompletableFuture.completedStage(false);
    }

    public CompletionStage<Void> dump(Path outputPath) {
        // Not supported
        LOGGER.warn("Not supported : dump");
        return CompletableFuture.completedStage(null);
    }

    private static Path devicePath() {
        return Path.of(StudioConfig.STUDIO_MOCK_DEVICE.getValue());
    }

    private void sendDevicePlugged(DeviceInfosDTO infosDTO) {
        JsonObject jo = JsonObject.mapFrom(infosDTO);
        LOGGER.debug("storyteller.plugged evt : {}", jo);
        eventBus.publish("storyteller.plugged", jo);
    }

    private void sendProgress(String id, double p) {
        eventBus.publish("storyteller.transfer." + id + ".progress", new JsonObject().put("progress", p));
    }

    private void sendDone(String id, boolean success) {
        eventBus.publish("storyteller.transfer." + id + ".done", new JsonObject().put("success", success));
    }

    private CompletionStage<List<StoryPackMetadata>> readPackIndex(Path deviceFolder) {
        // List binary pack files in mocked device folder
        try (Stream<Path> paths = Files.walk(deviceFolder).filter(Files::isRegularFile)) {
            return CompletableFuture.completedStage( //
                    paths.map(this::readBinaryPackFile) //
                            .filter(Optional::isPresent) //
                            .map(Optional::get) //
                            .collect(Collectors.toList()) //
            );
        } catch (IOException e) {
            LOGGER.error("Failed to read packs from mocked device", e);
            throw new StoryTellerException(e);
        }
    }

    private Optional<StoryPackMetadata> readBinaryPackFile(Path path) {
        LOGGER.debug("Reading pack file: {}", path);
        // Handle only binary file format
        if (path.toString().endsWith(".pack")) {
            try {
                LOGGER.debug("Reading binary pack metadata.");
                StoryPackMetadata meta = new BinaryStoryPackReader().readMetadata(path);
                if (meta != null) {
                    meta.setSectorSize((int) Math.ceil(Files.size(path) / 512d));
                    return Optional.of(meta);
                }
            } catch (IOException e) {
                LOGGER.atError().withThrowable(e).log("Failed to read binary-format pack {} from mocked device", path);
            }
        } else if (path.toString().endsWith(".zip")) {
            LOGGER.error("Mocked device should not contain archive-format packs");
        }
        // Ignore other files
        return Optional.empty();
    }

    private CompletionStage<String> copyPack(String opName, Path packFile, Path destFile) {
        String transferId = UUID.randomUUID().toString();
        // Perform transfer asynchronously, and send events on eventbus to monitor
        // progress and end of transfer
        Executor after2s = CompletableFuture.delayedExecutor(2, TimeUnit.SECONDS);
        CompletableFuture.runAsync(() -> {
            // Check that source and destination are available
            if (Files.notExists(packFile)) {
                LOGGER.warn("Cannot {} : pack doesn't exist {}", opName, packFile);
                sendDone(transferId, false);
                return;
            }
            if (Files.exists(destFile)) {
                LOGGER.warn("{} : destination already exists : {}", opName, destFile);
                sendDone(transferId, true);
                return;
            }
            try (InputStream input = new BufferedInputStream(Files.newInputStream(packFile));
                    OutputStream output = new BufferedOutputStream(Files.newOutputStream(destFile))) {
                long fileSize = Files.size(packFile);
                final byte[] buffer = new byte[BUFFER_SIZE];
                long count = 0;
                int n = 0;
                while ((n = input.read(buffer)) != -1) {
                    output.write(buffer, 0, n);
                    count += n;
                    // Send events on eventbus to monitor progress
                    double p = count / (double) fileSize;
                    if (LOGGER.isInfoEnabled()) {
                        LOGGER.info("Copying pack... {} ({} / {})", new DecimalFormat("#%").format(p),
                                FileUtils.readableByteSize(count), FileUtils.readableByteSize(fileSize));
                    }
                    sendProgress(transferId, p);
                }
                LOGGER.info("Pack copied ({})", transferId);
                // Send event on eventbus to signal end of transfer
                sendDone(transferId, true);
            } catch (IOException e) {
                LOGGER.error("Failed to {} on mocked device", opName, e);
                // Send event on eventbus to signal transfer failure
                sendDone(transferId, false);
            }
        }, after2s);
        return CompletableFuture.completedStage(transferId);
    }

    private DeviceInfosDTO getDeviceInfo() {
        try {
            FileStore mdFd = Files.getFileStore(devicePath());
            long total = mdFd.getTotalSpace();
            long used = mdFd.getTotalSpace() - mdFd.getUnallocatedSpace();

            // long files = paths.count();
            DeviceInfosDTO di = new DeviceInfosDTO();
            di.setUuid("mocked-device");
            di.setSerial("mocked-serial");
            di.setFirmware("mocked-version");
            di.setError(false);
            di.setPlugged(true);
            di.setDriver("raw"); // Simulate raw only
            di.setStorage(new StorageDTO(total, total - used, used));
            return di;
        } catch (IOException e) {
            LOGGER.error("Failed to initialize mocked device", e);
            throw new StoryTellerException(e);
        }
    }

    private MetaPackDTO toDto(StoryPackMetadata pack) {
        MetaPackDTO mp = new MetaPackDTO();
        mp.setUuid(pack.getUuid());
        mp.setFormat(PackFormat.RAW.getLabel());
        mp.setVersion(pack.getVersion());

        mp.setSectorSize(pack.getSectorSize());
        // add meta
        databaseMetadataService.getPackMetadata(pack.getUuid()).ifPresent(meta -> {//
            mp.setTitle(meta.getTitle());
            mp.setDescription(meta.getDescription());
            mp.setImage(meta.getThumbnail());
            mp.setOfficial(meta.isOfficial());
        });
        LOGGER.debug("toDto : {}", mp);
        return mp;
    }

}
