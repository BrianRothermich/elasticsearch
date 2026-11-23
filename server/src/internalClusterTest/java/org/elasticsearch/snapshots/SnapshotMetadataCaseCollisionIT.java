/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.snapshots;

import org.elasticsearch.common.UUIDs;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.repositories.blobstore.BlobStoreRepository;
import org.elasticsearch.test.ESIntegTestCase;
import org.elasticsearch.test.junit.annotations.TestLogging;

import java.nio.file.Files;
import java.util.Base64;
import java.util.Locale;

import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;

/** Checks that deleting a snapshot cannot remove a surviving clone's metadata on a case-insensitive filesystem. */
@ESIntegTestCase.ClusterScope(scope = ESIntegTestCase.Scope.TEST, numDataNodes = 0)
public class SnapshotMetadataCaseCollisionIT extends AbstractSnapshotIntegTestCase {
    @TestLogging(
        value = "org.elasticsearch.repositories.blobstore.BlobStoreRepository:TRACE",
        reason = "Trace the blob deletions that can remove a surviving clone's case-aliased metadata"
    )
    public void testCloneMetadataSurvivesDeletingCaseOnlyCounterpart() throws Exception {
        final var probe = createTempDir().resolve("CaseProbe");
        Files.createFile(probe);
        assumeTrue("requires a case-insensitive filesystem", Files.exists(probe.resolveSibling("caseprobe")));
        internalCluster().startNode();
        final String repo = "repo";
        final String index = "index";
        createRepository(repo, "fs");
        assertAcked(prepareCreate(index).setSettings(indexSettings(1, 0)));
        ensureGreen(index);

        // Position the seeded generator so that the next UUID has a case-only counterpart 104 allocations later.
        byte[] sourceUuidBytes = null;
        for (int i = 0; i < 256; i++) {
            final byte[] bytes = Base64.getUrlDecoder().decode(UUIDs.base64UUID());
            if ((bytes[0] & 0xff) == 59) {
                sourceUuidBytes = bytes;
                sourceUuidBytes[0]++;
                break;
            }
        }
        assertNotNull("did not reach the predecessor of the source metadata UUID", sourceUuidBytes);
        final byte[] otherUuidBytes = sourceUuidBytes.clone();
        otherUuidBytes[0] += 104;
        final String sourceUuid = Base64.getUrlEncoder().withoutPadding().encodeToString(sourceUuidBytes);
        final String otherUuid = Base64.getUrlEncoder().withoutPadding().encodeToString(otherUuidBytes);
        assertEquals(sourceUuid.toLowerCase(Locale.ROOT), otherUuid.toLowerCase(Locale.ROOT));
        logger.info(
            "LIFECYCLE generated UUIDs [{}] and [{}], sequences [{}] and [{}]",
            sourceUuid,
            otherUuid,
            sequence(sourceUuidBytes),
            sequence(otherUuidBytes)
        );

        createFullSnapshot(repo, "source");
        final var source = getSnapshot(repo, "source").snapshotId();
        final var data = getRepositoryData(repo);
        final var indexId = data.resolveIndexId(index);
        final String sourceBlob = data.indexMetaDataGenerations().indexMetaBlobId(source, indexId);
        final BlobStoreRepository repository = getRepositoryOnMaster(repo);
        final var originalMetadata = repository.getSnapshotIndexMetaData(data, source, indexId);
        logger.info(
            "LIFECYCLE source written: generation [{}], snapshot [{}], index [{}], blob [{}], settings version [{}], refresh interval [{}]",
            data.getGenId(),
            source,
            indexId,
            sourceBlob,
            originalMetadata.getSettingsVersion(),
            originalMetadata.getSettings().get("index.refresh_interval")
        );
        assertAcked(clusterAdmin().prepareCloneSnapshot(TEST_REQUEST_TIMEOUT, repo, "source", "clone").setIndices(index).get());
        final var clone = getSnapshot(repo, "clone").snapshotId();
        final var withClone = getRepositoryData(repo);
        logger.info(
            "LIFECYCLE clone created: generation [{}], clone [{}], blob [{}]",
            withClone.getGenId(),
            clone,
            withClone.indexMetaDataGenerations().indexMetaBlobId(clone, indexId)
        );
        assertAcked(startDeleteSnapshot(repo, "source").get());
        final var cloneOnly = getRepositoryData(repo);
        final var metadataAfterSourceDeletion = repository.getSnapshotIndexMetaData(cloneOnly, clone, indexId);
        logger.info(
            "LIFECYCLE source deleted: generation [{}], surviving clone [{}], blob [{}], settings version [{}]",
            cloneOnly.getGenId(),
            clone,
            cloneOnly.indexMetaDataGenerations().indexMetaBlobId(clone, indexId),
            metadataAfterSourceDeletion.getSettingsVersion()
        );
        assertAcked(indicesAdmin().prepareUpdateSettings(index).setSettings(Settings.builder().put("index.refresh_interval", "2s")));

        final int target = sequence(otherUuidBytes) - 1;
        boolean reachedTarget = false;
        for (int i = 0; i < 256; i++) {
            if (sequence(Base64.getUrlDecoder().decode(UUIDs.base64UUID())) == target) {
                reachedTarget = true;
                break;
            }
        }
        assertTrue("did not reach the predecessor of the other metadata UUID", reachedTarget);
        createFullSnapshot(repo, "other");
        final var other = getSnapshot(repo, "other").snapshotId();
        final var withOther = getRepositoryData(repo);
        // Capture the complete lifecycle before asserting, so the failure includes the deletion trace too.
        final var metadataAfterOtherSnapshot = repository.getSnapshotIndexMetaData(withOther, clone, indexId);
        final String otherBlob = withOther.indexMetaDataGenerations().indexMetaBlobId(other, indexId);
        logger.info(
            "LIFECYCLE other written: generation [{}], snapshot [{}], blob [{}], surviving clone [{}], blob [{}], settings version [{} -> {}], refresh interval [{} -> {}]",
            withOther.getGenId(),
            other,
            otherBlob,
            clone,
            withOther.indexMetaDataGenerations().indexMetaBlobId(clone, indexId),
            originalMetadata.getSettingsVersion(),
            metadataAfterOtherSnapshot.getSettingsVersion(),
            originalMetadata.getSettings().get("index.refresh_interval"),
            metadataAfterOtherSnapshot.getSettings().get("index.refresh_interval")
        );
        logger.info("LIFECYCLE deleting snapshot [{}] with metadata blob [{}]", other, otherBlob);
        assertAcked(startDeleteSnapshot(repo, "other").get());
        final var surviving = getRepositoryData(repo);
        logger.info(
            "LIFECYCLE other deleted: generation [{}], surviving snapshots [{}], clone [{}] still references [{}]",
            surviving.getGenId(),
            surviving.getSnapshotIds(),
            clone,
            surviving.indexMetaDataGenerations().indexMetaBlobId(clone, indexId)
        );
        assertTrue(surviving.getSnapshotIds().contains(clone));
        assertEquals(sourceBlob, surviving.indexMetaDataGenerations().indexMetaBlobId(clone, indexId));
        try {
            final var survivingMetadata = repository.getSnapshotIndexMetaData(surviving, clone, indexId);
            logger.info(
                "LIFECYCLE surviving clone metadata readable: blob [{}], settings version [{}]",
                sourceBlob,
                survivingMetadata.getSettingsVersion()
            );
            assertEquals(originalMetadata, metadataAfterOtherSnapshot);
            assertEquals(originalMetadata, survivingMetadata);
        } catch (SnapshotMissingException e) {
            logger.info("LIFECYCLE surviving clone [{}] lost metadata blob [{}]", clone, sourceBlob, e);
            throw e;
        }
    }

    private static int sequence(byte[] bytes) {
        return (bytes[0] & 0xff) | ((bytes[13] & 0xff) << 8) | ((bytes[1] & 0xff) << 16);
    }
}
