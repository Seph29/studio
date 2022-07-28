/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.core.v1.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import studio.core.v1.exception.StoryTellerException;
import studio.core.v1.model.StageNode;
import studio.core.v1.model.StoryPack;
import studio.core.v1.model.asset.AudioAsset;
import studio.core.v1.model.asset.AudioType;
import studio.core.v1.model.asset.ImageAsset;
import studio.core.v1.model.asset.ImageType;
import studio.core.v1.utils.audio.AudioConversion;
import studio.core.v1.utils.audio.ID3Tags;
import studio.core.v1.utils.image.ImageConversion;
import studio.core.v1.utils.security.SecurityUtils;
import studio.core.v1.utils.stream.StoppingConsumer;
import studio.core.v1.utils.stream.ThrowingFunction;

public class StoryPackConverter {

    private static final Logger LOGGER = LogManager.getLogger(StoryPackConverter.class);

    private Path libraryPath;
    private Path tmpDirPath;

    public StoryPackConverter(Path libraryPath, Path tmpDirPath) {
        this.libraryPath = libraryPath;
        this.tmpDirPath = tmpDirPath;
    }

    public Path convert(String packName, PackFormat outFormat, boolean allowEnriched) {
        Path packPath = libraryPath.resolve(packName);
        PackFormat inFormat = PackFormat.fromPath(packPath);
        LOGGER.info("Pack is in {} format. Converting to {} format", inFormat, outFormat);
        // check formats
        if (inFormat == outFormat) {
            throw new StoryTellerException("Pack is already in " + outFormat + " format : " + packPath.getFileName());
        }
        try {
            // Read pack
            LOGGER.info("Reading {} format pack", inFormat);
            StoryPack storyPack = inFormat.getReader().read(packPath);

            // Convert
            switch (outFormat) {
            case ARCHIVE:
                // Compress pack assets
                if (inFormat == PackFormat.RAW) {
                    LOGGER.info("Compressing pack assets");
                    processCompressed(storyPack);
                }
                // force enriched pack
                allowEnriched = true;
                break;
            case FS:
                // Prepare assets (RLE-encoded BMP, audio must already be MP3)
                LOGGER.info("Converting assets if necessary");
                processFirmware2dot4(storyPack);
                // force enriched pack
                allowEnriched = true;
                break;
            case RAW:
                // Uncompress pack assets
                if (hasCompressedAssets(storyPack)) {
                    LOGGER.info("Uncompressing pack assets");
                    processUncompressed(storyPack);
                }
                break;
            }

            // Write to temporary dir
            String destName = storyPack.getUuid() + ".converted_" + System.currentTimeMillis()
                    + outFormat.getExtension();
            Path tmpPath = tmpDirPath.resolve(destName);
            LOGGER.info("Writing {} format pack, using temporary : {}", outFormat, tmpPath);
            outFormat.getWriter().write(storyPack, tmpPath, allowEnriched);

            // Move to library
            Path destPath = libraryPath.resolve(destName);
            LOGGER.info("Moving {} format pack into local library: {}", outFormat, destPath);
            return Files.move(tmpPath, destPath);
        } catch (IOException e) {
            throw new StoryTellerException("Failed to convert " + inFormat + " pack to " + outFormat, e);
        }
    }

    public static boolean hasCompressedAssets(StoryPack pack) {
        for (StageNode node : pack.getStageNodes()) {
            if (node.getImage() != null && ImageType.BMP != node.getImage().getType()) {
                return true;
            }
            if (node.getAudio() != null && AudioType.WAV != node.getAudio().getType()) {
                return true;
            }
        }
        return false;
    }

    private static void processCompressed(StoryPack pack) {
        // Image
        processImageAssets(pack, ImageType.PNG, ThrowingFunction.unchecked(ia -> {
            byte[] imageData = ia.getRawData();
            if (ImageType.BMP == ia.getType()) {
                LOGGER.debug("Compressing BMP image asset into PNG");
                imageData = ImageConversion.bitmapToPng(imageData);
            }
            return imageData;
        }));
        // Audio
        processAudioAssets(pack, AudioType.OGG, ThrowingFunction.unchecked(aa -> {
            byte[] audioData = aa.getRawData();
            if (AudioType.WAV == aa.getType()) {
                LOGGER.debug("Compressing WAV audio asset into OGG");
                audioData = AudioConversion.waveToOgg(audioData);
            }
            return audioData;
        }));
    }

    private static void processUncompressed(StoryPack pack) {
        // Image
        processImageAssets(pack, ImageType.BMP, ThrowingFunction.unchecked(ia -> {
            byte[] imageData = ia.getRawData();
            // Convert from 4-bits depth / RLE encoding BMP
            if (ImageType.BMP == ia.getType() && imageData[28] == 0x04 && imageData[30] == 0x02) {
                LOGGER.debug("Uncompressing 4-bits/RLE BMP image asset into BMP");
                imageData = ImageConversion.anyToBitmap(imageData);
            }
            if (ImageType.JPEG == ia.getType() || ImageType.PNG == ia.getType()) {
                LOGGER.debug("Uncompressing {} image asset into BMP", ia.getType());
                imageData = ImageConversion.anyToBitmap(imageData);
            }
            return imageData;
        }));
        // Audio
        processAudioAssets(pack, AudioType.WAV, ThrowingFunction.unchecked(aa -> {
            byte[] audioData = aa.getRawData();
            if (AudioType.OGG == aa.getType()) {
                LOGGER.debug("Uncompressing OGG audio asset into WAV");
                audioData = AudioConversion.oggToWave(audioData);
            }
            if (AudioType.MP3 == aa.getType()) {
                LOGGER.debug("Uncompressing MP3 audio asset into WAV");
                audioData = AudioConversion.mp3ToWave(audioData);
            }
            return audioData;
        }));
    }

    private static void processFirmware2dot4(StoryPack pack) {
        // Image
        processImageAssets(pack, ImageType.BMP, ThrowingFunction.unchecked(ia -> {
            byte[] imageData = ia.getRawData();
            // Convert to 4-bits depth / RLE encoding BMP
            if (ImageType.BMP != ia.getType() || imageData[28] != 0x04 || imageData[30] != 0x02) {
                LOGGER.debug("Converting image asset into 4-bits/RLE BMP");
                imageData = ImageConversion.anyToRLECompressedBitmap(imageData);
            }
            return imageData;
        }));
        // Audio
        processAudioAssets(pack, AudioType.MP3, ThrowingFunction.unchecked(aa -> {
            byte[] audioData = aa.getRawData();
            if (AudioType.MP3 != aa.getType()) {
                LOGGER.debug("Converting audio asset into MP3");
                audioData = AudioConversion.anyToMp3(audioData);
            } else {
                // Remove potential ID3 tags
                audioData = ID3Tags.removeID3v1Tag(audioData);
                audioData = ID3Tags.removeID3v2Tag(audioData);
                // Check that the file is MONO / 44100Hz
                try (ByteArrayInputStream bais = new ByteArrayInputStream(audioData)) {
                    AudioFormat audioFormat = AudioSystem.getAudioFileFormat(bais).getFormat();
                    if (audioFormat.getChannels() != AudioConversion.CHANNELS
                            || audioFormat.getSampleRate() != AudioConversion.MP3_SAMPLE_RATE) {
                        LOGGER.debug("Re-encoding MP3 audio asset");
                        audioData = AudioConversion.anyToMp3(audioData);
                    }
                }
            }
            return audioData;
        }));
    }

    private static void processImageAssets(StoryPack pack, ImageType targetType,
            Function<ImageAsset, byte[]> imageProcessor) {
        // Cache prepared assets bytes
        Map<String, byte[]> assets = new ConcurrentHashMap<>();
        int nbNodes = pack.getStageNodes().size();
        AtomicInteger i = new AtomicInteger(0);

        // Multi-threaded processing : images
        pack.getStageNodes().parallelStream().forEach(StoppingConsumer.stopped(node -> {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Image from node {}/{} [{}]", i.incrementAndGet(), nbNodes,
                        Thread.currentThread().getName());
            }
            ImageAsset ia = node.getImage();
            if (ia != null) {
                byte[] imageData = ia.getRawData();
                String assetHash = SecurityUtils.sha1Hex(imageData);
                if (!assets.containsKey(assetHash)) {
                    // actual conversion
                    imageData = imageProcessor.apply(ia);
                    assets.put(assetHash, imageData);
                }
                // Use asset (already compressed) bytes from map
                ia.setRawData(assets.get(assetHash));
                ia.setType(targetType);
            }
        }));
        // Clean cache
        assets.clear();
    }

    private static void processAudioAssets(StoryPack pack, AudioType targetType,
            Function<AudioAsset, byte[]> audioProcessor) {
        // Cache prepared assets bytes
        Map<String, byte[]> assets = new ConcurrentHashMap<>();
        int nbNodes = pack.getStageNodes().size();
        AtomicInteger i = new AtomicInteger(0);

        // Multi-threaded processing : audio
        pack.getStageNodes().parallelStream().forEach(StoppingConsumer.stopped(node -> {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Audio from node {}/{} [{}]", i.incrementAndGet(), nbNodes,
                        Thread.currentThread().getName());
            }
            AudioAsset aa = node.getAudio();
            if (aa != null) {
                byte[] audioData = aa.getRawData();
                String assetHash = SecurityUtils.sha1Hex(audioData);
                if (!assets.containsKey(assetHash)) {
                    // actual conversion
                    audioData = audioProcessor.apply(aa);
                    assets.put(assetHash, audioData);
                }
                // Use asset (already compressed) bytes from map
                aa.setRawData(assets.get(assetHash));
                aa.setType(targetType);
            }
        }));
        // Clean cache
        assets.clear();
    }
}
