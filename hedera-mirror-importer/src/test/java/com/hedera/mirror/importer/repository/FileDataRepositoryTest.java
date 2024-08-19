/*
 * Copyright (C) 2019-2024 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.mirror.importer.repository;

import static com.hedera.mirror.common.util.DomainUtils.EMPTY_BYTE_ARRAY;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.primitives.Bytes;
import com.hedera.mirror.common.domain.entity.EntityId;
import com.hedera.mirror.common.domain.file.FileData;
import com.hedera.mirror.common.domain.transaction.TransactionType;
import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FileDataRepositoryTest extends AbstractRepositoryTest {

    private static final EntityId ADDRESS_BOOK_101 = EntityId.of("0.0.101");
    private static final EntityId ADDRESS_BOOK_102 = EntityId.of("0.0.102");

    @Resource
    private FileDataRepository fileDataRepository;

    @Test
    void findFilesInRange() {
        List<FileData> fileDataList = new ArrayList<>();
        fileDataList.add(fileData(1, 123, TransactionType.FILECREATE.getProtoId()));
        fileDataList.add(fileData(2, ADDRESS_BOOK_102.getId(), TransactionType.FILECREATE.getProtoId()));
        fileDataList.add(fileData(3, ADDRESS_BOOK_102.getId(), TransactionType.FILEAPPEND.getProtoId()));
        fileDataList.add(fileData(4, ADDRESS_BOOK_102.getId(), TransactionType.FILEAPPEND.getProtoId()));
        fileDataList.add(fileData(5, ADDRESS_BOOK_102.getId(), TransactionType.FILEAPPEND.getProtoId()));
        fileDataList.add(fileData(6, 123, TransactionType.FILEUPDATE.getProtoId()));
        fileDataList.add(fileData(7, 123, TransactionType.FILEAPPEND.getProtoId()));
        fileDataRepository.saveAll(fileDataList);

        assertThat(fileDataRepository.findFilesInRange(
                        2, 7, ADDRESS_BOOK_102.getId(), TransactionType.FILEAPPEND.getProtoId()))
                .isNotNull()
                .hasSize(3)
                .extracting(FileData::getConsensusTimestamp)
                .containsSequence(3L, 4L, 5L);
    }

    @Test
    void findFilesOfTransactionTypesInRange() {
        List<FileData> fileDataList = new ArrayList<>();
        fileDataList.add(fileData(1, 123, TransactionType.FILECREATE.getProtoId()));
        FileData fileData = fileData(2, ADDRESS_BOOK_102.getId(), TransactionType.FILEUPDATE.getProtoId());
        fileDataList.add(fileData);
        fileDataList.add(fileData(3, ADDRESS_BOOK_102.getId(), TransactionType.FILEAPPEND.getProtoId()));
        fileDataList.add(fileData(4, ADDRESS_BOOK_102.getId(), TransactionType.FILEAPPEND.getProtoId()));
        fileDataList.add(fileData(5, ADDRESS_BOOK_102.getId(), TransactionType.FILEAPPEND.getProtoId()));
        fileDataList.add(fileData(6, 123, TransactionType.FILEUPDATE.getProtoId()));
        fileDataList.add(fileData(7, 123, TransactionType.FILEAPPEND.getProtoId()));
        fileDataRepository.saveAll(fileDataList);

        List<Integer> transactionTypes =
                List.of(TransactionType.FILECREATE.getProtoId(), TransactionType.FILEUPDATE.getProtoId());
        assertThat(fileDataRepository.findLatestMatchingFile(5, ADDRESS_BOOK_102.getId(), transactionTypes))
                .contains(fileData);
    }

    @Test
    void findAddressBookFilesInRange() {
        List<FileData> fileDataList = new ArrayList<>();
        fileDataList.add(fileData(1, ADDRESS_BOOK_101.getId(), TransactionType.FILECREATE.getProtoId()));
        fileDataList.add(fileData(2, ADDRESS_BOOK_102.getId(), TransactionType.FILECREATE.getProtoId()));
        fileDataList.add(fileData(3, ADDRESS_BOOK_101.getId(), TransactionType.FILEUPDATE.getProtoId()));
        fileDataList.add(fileData(4, ADDRESS_BOOK_101.getId(), TransactionType.FILEAPPEND.getProtoId()));
        fileDataList.add(fileData(5, ADDRESS_BOOK_102.getId(), TransactionType.FILEUPDATE.getProtoId()));
        fileDataList.add(fileData(6, ADDRESS_BOOK_102.getId(), TransactionType.FILEAPPEND.getProtoId()));
        fileDataList.add(fileData(7, ADDRESS_BOOK_101.getId(), TransactionType.FILEUPDATE.getProtoId()));
        fileDataList.add(fileData(8, ADDRESS_BOOK_102.getId(), TransactionType.FILEUPDATE.getProtoId()));
        fileDataRepository.saveAll(fileDataList);

        assertThat(fileDataRepository.findAddressBooksBetween(2, 5, 10))
                .isNotNull()
                .hasSize(2)
                .extracting(FileData::getConsensusTimestamp)
                .containsSequence(3L, 4L);
    }

    @Test
    void findAddressBookFilesWithLimit() {
        List<FileData> fileDataList = new ArrayList<>();
        fileDataList.add(fileData(1, ADDRESS_BOOK_101.getId(), TransactionType.FILECREATE.getProtoId()));
        fileDataList.add(fileData(2, ADDRESS_BOOK_102.getId(), TransactionType.FILECREATE.getProtoId()));
        fileDataList.add(fileData(3, ADDRESS_BOOK_101.getId(), TransactionType.FILEUPDATE.getProtoId()));
        fileDataList.add(fileData(4, ADDRESS_BOOK_101.getId(), TransactionType.FILEAPPEND.getProtoId()));
        fileDataList.add(fileData(5, ADDRESS_BOOK_102.getId(), TransactionType.FILEUPDATE.getProtoId()));
        fileDataList.add(fileData(6, ADDRESS_BOOK_102.getId(), TransactionType.FILEAPPEND.getProtoId()));
        fileDataList.add(fileData(7, ADDRESS_BOOK_101.getId(), TransactionType.FILEUPDATE.getProtoId()));
        fileDataList.add(fileData(8, ADDRESS_BOOK_102.getId(), TransactionType.FILEUPDATE.getProtoId()));
        fileDataRepository.saveAll(fileDataList);

        assertThat(fileDataRepository.findAddressBooksBetween(2, 10, 5))
                .isNotNull()
                .hasSize(5)
                .extracting(FileData::getConsensusTimestamp)
                .containsSequence(3L, 4L, 5L, 6L, 7L);
    }

    @Test
    void getFileAtTimestamp() {
        softly.assertThat(fileDataRepository.getFileAtTimestamp(1101, domainBuilder.timestamp()))
                .isEmpty();

        // Create
        var create = domainBuilder.fileData().persist();
        long entityId = create.getEntityId().getId();
        var expected = FileData.builder()
                .consensusTimestamp(create.getConsensusTimestamp())
                .entityId(create.getEntityId())
                .fileData(create.getFileData())
                .transactionType(null)
                .build();
        softly.assertThat(fileDataRepository.getFileAtTimestamp(entityId, create.getConsensusTimestamp()))
                .contains(expected);
        softly.assertThat(fileDataRepository.getFileAtTimestamp(entityId, create.getConsensusTimestamp() - 1))
                .isEmpty();

        // Append
        var append = domainBuilder
                .fileData()
                .customize(
                        f -> f.entityId(create.getEntityId()).transactionType(TransactionType.FILEAPPEND.getProtoId()))
                .persist();
        expected.setFileData(Bytes.concat(create.getFileData(), append.getFileData()));
        softly.assertThat(fileDataRepository.getFileAtTimestamp(entityId, append.getConsensusTimestamp()))
                .contains(expected);

        // Update without changing content
        var update = domainBuilder
                .fileData()
                .customize(f -> f.entityId(create.getEntityId())
                        .fileData(EMPTY_BYTE_ARRAY)
                        .transactionType(TransactionType.FILEUPDATE.getProtoId()))
                .persist();
        softly.assertThat(fileDataRepository.getFileAtTimestamp(entityId, update.getConsensusTimestamp()))
                .contains(expected);

        // Update, change content
        var updateContent = domainBuilder
                .fileData()
                .customize(
                        f -> f.entityId(create.getEntityId()).transactionType(TransactionType.FILEUPDATE.getProtoId()))
                .persist();
        softly.assertThat(fileDataRepository.getFileAtTimestamp(entityId, updateContent.getConsensusTimestamp() - 1))
                .contains(expected);
        expected.setConsensusTimestamp(updateContent.getConsensusTimestamp());
        expected.setFileData(updateContent.getFileData());
        softly.assertThat(fileDataRepository.getFileAtTimestamp(entityId, updateContent.getConsensusTimestamp()))
                .contains(expected);
    }

    private FileData fileData(long consensusTimestamp, long fileId, int transactionType) {
        FileData fileData = new FileData();
        fileData.setConsensusTimestamp(consensusTimestamp);
        fileData.setFileData("some file data".getBytes());
        fileData.setEntityId(EntityId.of(0, 0, fileId));
        fileData.setTransactionType(transactionType);
        return fileData;
    }
}
