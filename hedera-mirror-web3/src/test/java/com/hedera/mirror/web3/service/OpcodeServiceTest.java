/*
 * Copyright (C) 2024 Hedera Hashgraph, LLC
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

package com.hedera.mirror.web3.service;

import static com.hedera.mirror.common.domain.entity.EntityType.TOKEN;
import static com.hedera.mirror.common.domain.transaction.TransactionType.ETHEREUMTRANSACTION;
import static com.hedera.mirror.common.util.CommonUtils.instant;
import static com.hedera.mirror.common.util.DomainUtils.EVM_ADDRESS_LENGTH;
import static com.hedera.mirror.web3.evm.utils.EvmTokenUtils.entityIdFromEvmAddress;
import static com.hedera.mirror.web3.evm.utils.EvmTokenUtils.toAddress;
import static com.hedera.mirror.web3.utils.ContractCallTestUtil.CREATE_TOKEN_VALUE;
import static com.hedera.mirror.web3.utils.ContractCallTestUtil.TRANSACTION_GAS_LIMIT;
import static com.hedera.mirror.web3.validation.HexValidator.HEX_PREFIX;
import static com.hedera.services.stream.proto.ContractAction.ResultDataCase.OUTPUT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.doAnswer;

import com.google.protobuf.ByteString;
import com.hedera.mirror.common.domain.balance.AccountBalance;
import com.hedera.mirror.common.domain.contract.ContractResult;
import com.hedera.mirror.common.domain.contract.ContractTransactionHash;
import com.hedera.mirror.common.domain.entity.Entity;
import com.hedera.mirror.common.domain.entity.EntityId;
import com.hedera.mirror.common.domain.entity.EntityType;
import com.hedera.mirror.common.domain.token.Token;
import com.hedera.mirror.common.domain.token.TokenTypeEnum;
import com.hedera.mirror.common.domain.transaction.EthereumTransaction;
import com.hedera.mirror.common.domain.transaction.Transaction;
import com.hedera.mirror.common.domain.transaction.TransactionType;
import com.hedera.mirror.common.util.DomainUtils;
import com.hedera.mirror.rest.model.OpcodesResponse;
import com.hedera.mirror.web3.common.ContractCallContext;
import com.hedera.mirror.web3.common.TransactionHashParameter;
import com.hedera.mirror.web3.common.TransactionIdOrHashParameter;
import com.hedera.mirror.web3.common.TransactionIdParameter;
import com.hedera.mirror.web3.evm.contracts.execution.traceability.Opcode;
import com.hedera.mirror.web3.evm.contracts.execution.traceability.OpcodeTracerOptions;
import com.hedera.mirror.web3.evm.store.accessor.EntityDatabaseAccessor;
import com.hedera.mirror.web3.exception.EntityNotFoundException;
import com.hedera.mirror.web3.service.model.ContractDebugParameters;
import com.hedera.mirror.web3.web3j.generated.DynamicEthCalls;
import com.hedera.mirror.web3.web3j.generated.ExchangeRatePrecompile;
import com.hedera.mirror.web3.web3j.generated.NestedCalls;
import com.hedera.mirror.web3.web3j.generated.NestedCalls.HederaToken;
import com.hedera.mirror.web3.web3j.generated.NestedCalls.TokenKey;
import com.hedera.node.app.service.evm.contracts.execution.HederaEvmTransactionProcessingResult;
import com.hedera.node.app.service.evm.store.contracts.precompile.codec.EvmEncodingFacade;
import com.hedera.services.store.contracts.precompile.codec.KeyValueWrapper.KeyValueType;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.web3j.abi.TypeEncoder;
import org.web3j.tx.Contract;

@RequiredArgsConstructor
class OpcodeServiceTest extends AbstractContractCallServiceTest {

    private final long AMOUNT = 0L;
    private final String SUCCESS_PREFIX = "0x0000000000000000000000000000000000000000000000000000000000000020";

    private final OpcodeService opcodeService;
    private final EntityDatabaseAccessor entityDatabaseAccessor;

    @Captor
    private ArgumentCaptor<ContractDebugParameters> serviceParametersCaptor;

    @Captor
    private ArgumentCaptor<Long> gasCaptor;

    private HederaEvmTransactionProcessingResult resultCaptor;

    private ContractCallContext contextCaptor;

    private final EvmEncodingFacade evmEncoder;

    @BeforeEach
    void setUpArgumentCaptors() {
        doAnswer(invocation -> {
                    final var transactionProcessingResult =
                            (HederaEvmTransactionProcessingResult) invocation.callRealMethod();
                    resultCaptor = transactionProcessingResult;
                    contextCaptor = ContractCallContext.get();
                    return transactionProcessingResult;
                })
                .when(processor)
                .execute(serviceParametersCaptor.capture(), gasCaptor.capture());
    }

    @Test
    void callWithContractTransactionHashNotFoundExceptionTest() {
        final var contract = testWeb3jService.deploy(NestedCalls::deploy);
        final var senderEntity = accountPersist();
        final var treasuryEntity = accountPersist();
        final var autoRenewAccount = accountPersist();
        final var hederaToken = getHederaToken(TokenTypeEnum.FUNGIBLE_COMMON, treasuryEntity, autoRenewAccount);

        final OpcodeTracerOptions options = new OpcodeTracerOptions();
        final var functionCall =
                contract.call_createFungibleTokenAndGetIsTokenAndGetDefaultFreezeStatusAndGetDefaultKycStatus(
                        hederaToken, BigInteger.ONE, BigInteger.ONE);
        final var transactionIdOrHash = setUp(
                ETHEREUMTRANSACTION,
                contract,
                functionCall.encodeFunctionCall().getBytes(),
                false,
                true,
                senderEntity.toEntityId(),
                domainBuilder.timestamp());

        assertThatExceptionOfType(EntityNotFoundException.class)
                .isThrownBy(() -> opcodeService.processOpcodeCall(transactionIdOrHash, options))
                .withMessage("Contract transaction hash not found: " + transactionIdOrHash);
    }

    @Test
    void callWithContractResultNotFoundExceptionTest() {
        final var contract = testWeb3jService.deploy(ExchangeRatePrecompile::deploy);
        final var functionCall = contract.call_tinybarsToTinycents(BigInteger.TEN);
        final var callData = functionCall.encodeFunctionCall().getBytes();
        final var senderEntity = accountPersist();
        final var consensusTimestamp = domainBuilder.timestamp();
        final var transactionIdOrHash = setUp(
                TransactionType.CONTRACTCALL,
                contract,
                callData,
                true,
                false,
                senderEntity.toEntityId(),
                consensusTimestamp);
        final OpcodeTracerOptions options = new OpcodeTracerOptions();

        assertThatExceptionOfType(EntityNotFoundException.class)
                .isThrownBy(() -> opcodeService.processOpcodeCall(transactionIdOrHash, options))
                .withMessage("Contract result not found: " + consensusTimestamp);
    }

    @Test
    void callWithTransactionNotFoundExceptionTest() {
        final var contract = testWeb3jService.deploy(ExchangeRatePrecompile::deploy);
        final var functionCall = contract.call_tinybarsToTinycents(BigInteger.TEN);
        final var callData = functionCall.encodeFunctionCall().getBytes();
        final var senderEntity = accountPersist();

        final var transactionIdOrHash = setUp(
                TransactionType.CONTRACTCALL,
                contract,
                callData,
                false,
                true,
                senderEntity.toEntityId(),
                domainBuilder.timestamp());
        final OpcodeTracerOptions options = new OpcodeTracerOptions();

        assertThatExceptionOfType(EntityNotFoundException.class)
                .isThrownBy(() -> opcodeService.processOpcodeCall(transactionIdOrHash, options))
                .withMessage("Transaction not found: " + transactionIdOrHash);
    }

    @ParameterizedTest
    @MethodSource("tracerOptions")
    void callWithDifferentCombinationsOfTracerOptions(final OpcodeTracerOptions options) {
        final var contract = testWeb3jService.deploy(DynamicEthCalls::deploy);
        final var treasuryEntity = accountPersist();
        final var treasuryAddress = toAddress(treasuryEntity.toEntityId());
        final var tokenEntity = nftPersist(treasuryEntity.toEntityId());
        final var tokenAddress = toAddress(tokenEntity.getTokenId());
        final var senderEntity = accountPersist();

        final var functionCall = contract.send_mintTokenGetTotalSupplyAndBalanceOfTreasury(
                tokenAddress.toHexString(),
                BigInteger.ONE,
                List.of(new byte[][] {ByteString.copyFromUtf8("firstMeta").toByteArray()}),
                treasuryAddress.toHexString());

        final var callData = functionCall.encodeFunctionCall().getBytes();
        final var transactionIdOrHash = setUp(
                ETHEREUMTRANSACTION,
                contract,
                callData,
                true,
                true,
                senderEntity.toEntityId(),
                domainBuilder.timestamp());

        final var opcodesResponse = opcodeService.processOpcodeCall(transactionIdOrHash, options);

        verifyOpcodesResponse(opcodesResponse, options);
    }

    @ParameterizedTest
    @CsvSource(
            textBlock =
                    """
                    CONTRACT_ID,                ADMIN_KEY,
                    CONTRACT_ID,                KYC_KEY,
                    CONTRACT_ID,                FREEZE_KEY,
                    CONTRACT_ID,                WIPE_KEY,
                    CONTRACT_ID,                SUPPLY_KEY,
                    CONTRACT_ID,                FEE_SCHEDULE_KEY,
                    CONTRACT_ID,                PAUSE_KEY,
                    ED25519,                    ADMIN_KEY,
                    ED25519,                    KYC_KEY,
                    ED25519,                    FREEZE_KEY,
                    ED25519,                    WIPE_KEY,
                    ED25519,                    SUPPLY_KEY,
                    ED25519,                    FEE_SCHEDULE_KEY,
                    ED25519,                    PAUSE_KEY,
                    ECDSA_SECPK256K1,           ADMIN_KEY,
                    ECDSA_SECPK256K1,           KYC_KEY,
                    ECDSA_SECPK256K1,           FREEZE_KEY,
                    ECDSA_SECPK256K1,           WIPE_KEY,
                    ECDSA_SECPK256K1,           SUPPLY_KEY,
                    ECDSA_SECPK256K1,           FEE_SCHEDULE_KEY,
                    ECDSA_SECPK256K1,           PAUSE_KEY,
                    DELEGATABLE_CONTRACT_ID,    ADMIN_KEY,
                    DELEGATABLE_CONTRACT_ID,    KYC_KEY,
                    DELEGATABLE_CONTRACT_ID,    FREEZE_KEY,
                    DELEGATABLE_CONTRACT_ID,    WIPE_KEY,
                    DELEGATABLE_CONTRACT_ID,    SUPPLY_KEY,
                    DELEGATABLE_CONTRACT_ID,    FEE_SCHEDULE_KEY,
                    DELEGATABLE_CONTRACT_ID,    PAUSE_KEY,
                    """)
    void updateTokenKeysAndGetUpdatedTokenKeyForFungibleToken(final KeyValueType keyValueType, final KeyType keyType) {
        // Given
        final var tokenEntity = fungibleTokenPersist();
        final var tokenAddress = toAddress(tokenEntity.getTokenId());
        final var contract = testWeb3jService.deploy(NestedCalls::deploy);
        final var contractAddress = contract.getContractAddress();

        final var keyValue = getKeyValueForType(keyValueType, contractAddress);
        final var tokenKey = new TokenKey(keyType.getKeyTypeNumeric(), keyValue);
        final var expectedResult = TypeEncoder.encode(keyValue);
        final var expectedResultBytes = Bytes.fromHexString(expectedResult).toArray();

        final var functionCall = contract.call_updateTokenKeysAndGetUpdatedTokenKey(
                tokenAddress.toHexString(), List.of(tokenKey), keyType.getKeyTypeNumeric());
        final var callData =
                Bytes.fromHexString(functionCall.encodeFunctionCall()).toArray();

        final var options = new OpcodeTracerOptions();
        final var transactionIdOrHash = setUpEthereumTransaction(contract, callData, expectedResultBytes);

        // When
        final var opcodesResponse = opcodeService.processOpcodeCall(transactionIdOrHash, options);

        // Then
        verifyOpcodesResponseWithExpectedReturnValue(opcodesResponse, options, SUCCESS_PREFIX + expectedResult);
    }

    @ParameterizedTest
    @CsvSource(
            textBlock =
                    """
                    CONTRACT_ID,                ADMIN_KEY,
                    CONTRACT_ID,                KYC_KEY,
                    CONTRACT_ID,                FREEZE_KEY,
                    CONTRACT_ID,                WIPE_KEY,
                    CONTRACT_ID,                SUPPLY_KEY,
                    CONTRACT_ID,                FEE_SCHEDULE_KEY,
                    CONTRACT_ID,                PAUSE_KEY,
                    ED25519,                    ADMIN_KEY,
                    ED25519,                    KYC_KEY,
                    ED25519,                    FREEZE_KEY,
                    ED25519,                    WIPE_KEY,
                    ED25519,                    SUPPLY_KEY,
                    ED25519,                    FEE_SCHEDULE_KEY,
                    ED25519,                    PAUSE_KEY,
                    ECDSA_SECPK256K1,           ADMIN_KEY,
                    ECDSA_SECPK256K1,           KYC_KEY,
                    ECDSA_SECPK256K1,           FREEZE_KEY,
                    ECDSA_SECPK256K1,           WIPE_KEY,
                    ECDSA_SECPK256K1,           SUPPLY_KEY,
                    ECDSA_SECPK256K1,           FEE_SCHEDULE_KEY,
                    ECDSA_SECPK256K1,           PAUSE_KEY,
                    DELEGATABLE_CONTRACT_ID,    ADMIN_KEY,
                    DELEGATABLE_CONTRACT_ID,    KYC_KEY,
                    DELEGATABLE_CONTRACT_ID,    FREEZE_KEY,
                    DELEGATABLE_CONTRACT_ID,    WIPE_KEY,
                    DELEGATABLE_CONTRACT_ID,    SUPPLY_KEY,
                    DELEGATABLE_CONTRACT_ID,    FEE_SCHEDULE_KEY,
                    DELEGATABLE_CONTRACT_ID,    PAUSE_KEY
                    """)
    void updateTokenKeysAndGetUpdatedTokenKeyForNFT(final KeyValueType keyValueType, final KeyType keyType) {
        // Given
        final var treasuryEntity = accountPersist();
        final var tokenEntity = nftPersist(treasuryEntity.toEntityId());
        final var tokenAddress = toAddress(tokenEntity.getTokenId());
        final var contract = testWeb3jService.deploy(NestedCalls::deploy);
        final var contractAddress = contract.getContractAddress();

        final var keyValue = getKeyValueForType(keyValueType, contractAddress);
        final var tokenKey = new TokenKey(keyType.getKeyTypeNumeric(), keyValue);
        final var expectedResult = TypeEncoder.encode(keyValue);
        final var expectedResultBytes = Bytes.fromHexString(expectedResult).toArray();

        final var functionCall = contract.call_updateTokenKeysAndGetUpdatedTokenKey(
                tokenAddress.toHexString(), List.of(tokenKey), keyType.getKeyTypeNumeric());
        final var callData =
                Bytes.fromHexString(functionCall.encodeFunctionCall()).toArray();

        final var options = new OpcodeTracerOptions();
        final var transactionIdOrHash = setUpEthereumTransaction(contract, callData, expectedResultBytes);

        // When
        final var opcodesResponse = opcodeService.processOpcodeCall(transactionIdOrHash, options);

        // Then
        verifyOpcodesResponseWithExpectedReturnValue(opcodesResponse, options, SUCCESS_PREFIX + expectedResult);
    }

    @ParameterizedTest
    @CsvSource(textBlock = """
                FUNGIBLE_COMMON
                NON_FUNGIBLE_UNIQUE
                """)
    void updateTokenExpiryAndGetUpdatedTokenExpiry(final TokenTypeEnum tokenType) {
        // Given
        final var treasuryEntity = accountPersist();
        final var tokenEntityId = tokenType == TokenTypeEnum.FUNGIBLE_COMMON
                ? fungibleTokenPersist(treasuryEntity)
                : nftPersist(treasuryEntity.toEntityId());
        final var tokenAddress = toAddress(tokenEntityId.getTokenId());
        final var contract = testWeb3jService.deploy(NestedCalls::deploy);
        final var autoRenewAccount = accountPersist();
        final var tokenExpiry = getTokenExpiry(autoRenewAccount);

        final var functionCall =
                contract.call_updateTokenExpiryAndGetUpdatedTokenExpiry(tokenAddress.toHexString(), tokenExpiry);
        final var callData =
                Bytes.fromHexString(functionCall.encodeFunctionCall()).toArray();
        final var expectedResult = TypeEncoder.encode(tokenExpiry);
        final var expectedResultBytes = Bytes.fromHexString(expectedResult).toArray();

        final var transactionIdOrHash = setUpEthereumTransaction(contract, callData, expectedResultBytes);
        final OpcodeTracerOptions options = new OpcodeTracerOptions();

        // When
        final var opcodesResponse = opcodeService.processOpcodeCall(transactionIdOrHash, options);

        // Then
        verifyOpcodesResponseWithExpectedReturnValue(opcodesResponse, options, HEX_PREFIX + expectedResult);
    }

    @ParameterizedTest
    @CsvSource(textBlock = """
                FUNGIBLE_COMMON
                NON_FUNGIBLE_UNIQUE
                """)
    void updateTokenInfoAndGetUpdatedTokenInfoSymbol(final TokenTypeEnum tokenType) {
        // Given
        final var treasuryEntity = accountPersist();
        final var autoRenewAccount = accountPersist();
        final var tokenEntityId = tokenType == TokenTypeEnum.FUNGIBLE_COMMON
                ? fungibleTokenPersist(treasuryEntity)
                : nftPersist(treasuryEntity.toEntityId());
        final var tokenAddress = toAddress(tokenEntityId.getTokenId());
        final var contract = testWeb3jService.deploy(NestedCalls::deploy);
        final var tokenInfo = getHederaToken(tokenType, treasuryEntity, autoRenewAccount);

        final var functionCall =
                contract.call_updateTokenInfoAndGetUpdatedTokenInfoSymbol(tokenAddress.toHexString(), tokenInfo);
        final var callData =
                Bytes.fromHexString(functionCall.encodeFunctionCall()).toArray();
        final var expectedResultBytes = tokenInfo.symbol.getBytes();
        final var expectedResult = evmEncoder.encodeSymbol(tokenInfo.symbol).toHexString();

        final var transactionIdOrHash = setUpEthereumTransaction(contract, callData, expectedResultBytes);
        final OpcodeTracerOptions options = new OpcodeTracerOptions();

        // When
        final var opcodesResponse = opcodeService.processOpcodeCall(transactionIdOrHash, options);
        // Then
        verifyOpcodesResponseWithExpectedReturnValue(opcodesResponse, options, expectedResult);
    }

    @ParameterizedTest
    @CsvSource(textBlock = """
                FUNGIBLE_COMMON
                NON_FUNGIBLE_UNIQUE
                """)
    void updateTokenInfoAndGetUpdatedTokenInfoName(final TokenTypeEnum tokenType) {
        // Given
        final var treasuryEntity = accountPersist();
        final var autoRenewAccount = accountPersist();
        final var tokenEntityId = tokenType == TokenTypeEnum.FUNGIBLE_COMMON
                ? fungibleTokenPersist(treasuryEntity)
                : nftPersist(treasuryEntity.toEntityId());
        final var tokenAddress = toAddress(tokenEntityId.getTokenId());
        final var contract = testWeb3jService.deploy(NestedCalls::deploy);
        final var tokenInfo = getHederaToken(tokenType, treasuryEntity, autoRenewAccount);

        final var functionCall =
                contract.call_updateTokenInfoAndGetUpdatedTokenInfoName(tokenAddress.toHexString(), tokenInfo);
        final var callData =
                Bytes.fromHexString(functionCall.encodeFunctionCall()).toArray();
        final var expectedResultBytes = tokenInfo.name.getBytes();
        final var expectedResult = evmEncoder.encodeName(tokenInfo.name).toHexString();

        final var transactionIdOrHash = setUpEthereumTransaction(contract, callData, expectedResultBytes);
        final OpcodeTracerOptions options = new OpcodeTracerOptions();

        // When
        final var opcodesResponse = opcodeService.processOpcodeCall(transactionIdOrHash, options);

        // Then
        verifyOpcodesResponseWithExpectedReturnValue(opcodesResponse, options, expectedResult);
    }

    @ParameterizedTest
    @CsvSource(textBlock = """
                FUNGIBLE_COMMON
                NON_FUNGIBLE_UNIQUE
                """)
    void updateTokenInfoAndGetUpdatedTokenInfoMemo(final TokenTypeEnum tokenType) {
        // Given
        final var treasuryEntity = accountPersist();
        final var autoRenewAccount = accountPersist();
        final var tokenEntityId = tokenType == TokenTypeEnum.FUNGIBLE_COMMON
                ? fungibleTokenPersist(treasuryEntity)
                : nftPersist(treasuryEntity.toEntityId());
        final var tokenAddress = toAddress(tokenEntityId.getTokenId());
        final var contract = testWeb3jService.deploy(NestedCalls::deploy);
        final var tokenInfo = getHederaToken(tokenType, treasuryEntity, autoRenewAccount);

        final var functionCall =
                contract.call_updateTokenInfoAndGetUpdatedTokenInfoMemo(tokenAddress.toHexString(), tokenInfo);
        final var callData =
                Bytes.fromHexString(functionCall.encodeFunctionCall()).toArray();
        final var expectedResultBytes = tokenInfo.memo.getBytes();
        final var expectedResult = evmEncoder.encodeName(tokenInfo.memo).toHexString();

        final var transactionIdOrHash = setUpEthereumTransaction(contract, callData, expectedResultBytes);
        final OpcodeTracerOptions options = new OpcodeTracerOptions();

        // When
        final var opcodesResponse = opcodeService.processOpcodeCall(transactionIdOrHash, options);

        // Then
        verifyOpcodesResponseWithExpectedReturnValue(opcodesResponse, options, expectedResult);
    }

    @ParameterizedTest
    @CsvSource(textBlock = """
                FUNGIBLE_COMMON
                NON_FUNGIBLE_UNIQUE
                """)
    void deleteTokenAndGetTokenInfoIsDeleted(final TokenTypeEnum tokenType) {
        // Given
        final var treasuryEntity = accountPersist();
        final var tokenEntityId = tokenType == TokenTypeEnum.FUNGIBLE_COMMON
                ? fungibleTokenPersist()
                : nftPersist(treasuryEntity.toEntityId());
        final var tokenAddress = toAddress(tokenEntityId.getTokenId());
        final var contract = testWeb3jService.deploy(NestedCalls::deploy);

        final var functionCall = contract.call_deleteTokenAndGetTokenInfoIsDeleted(tokenAddress.toHexString());
        final var callData =
                Bytes.fromHexString(functionCall.encodeFunctionCall()).toArray();
        final byte[] expectedResultBytes = {1};
        final var expectedResult = DomainUtils.bytesToHex(DomainUtils.leftPadBytes(expectedResultBytes, 32));

        final var transactionIdOrHash = setUpEthereumTransaction(contract, callData, expectedResultBytes);
        final OpcodeTracerOptions options = new OpcodeTracerOptions();

        // When
        final var opcodesResponse = opcodeService.processOpcodeCall(transactionIdOrHash, options);

        // Then
        verifyOpcodesResponseWithExpectedReturnValue(opcodesResponse, options, HEX_PREFIX + expectedResult);
    }

    @ParameterizedTest
    @CsvSource(
            textBlock =
                    """
                true, false, true, true
                false, false, false, false
                true, true, true, true
                """)
    void createFungibleTokenAndGetIsTokenAndGetDefaultFreezeStatusAndGetDefaultKycStatus(
            final boolean withKeys,
            final boolean inheritKey,
            final boolean defaultKycStatus,
            final boolean defaultFreezeStatus) {
        // Given
        final var treasuryEntity = accountPersistWithBalance(CREATE_TOKEN_VALUE);
        final var contract = testWeb3jService.deploy(NestedCalls::deploy);
        final var tokenInfo = getHederaToken(
                TokenTypeEnum.FUNGIBLE_COMMON, withKeys, inheritKey, defaultFreezeStatus, treasuryEntity);
        tokenInfo.freezeDefault = defaultFreezeStatus;

        final var functionCall =
                contract.call_createFungibleTokenAndGetIsTokenAndGetDefaultFreezeStatusAndGetDefaultKycStatus(
                        tokenInfo, BigInteger.ONE, BigInteger.ONE);
        final var callData =
                Bytes.fromHexString(functionCall.encodeFunctionCall()).toArray();
        final var expectedResult = getExpectedResultFromBooleans(defaultKycStatus, defaultFreezeStatus, true);
        final var expectedResultBytes = expectedResult.getBytes();

        final var transactionIdOrHash =
                setUpEthereumTransactionWithSenderBalance(contract, callData, CREATE_TOKEN_VALUE, expectedResultBytes);
        final OpcodeTracerOptions options = new OpcodeTracerOptions();

        // When
        final var opcodesResponse = opcodeService.processOpcodeCall(transactionIdOrHash, options);

        // Then
        verifyOpcodesResponseWithExpectedReturnValue(opcodesResponse, options, HEX_PREFIX + expectedResult);
    }

    @ParameterizedTest
    @CsvSource(
            textBlock =
                    """
                true, false, true, true
                false, false, false, false
                true, true, true, true
                """)
    void createNFTAndGetIsTokenAndGetDefaultFreezeStatusAndGetDefaultKycStatus(
            final boolean withKeys,
            final boolean inheritKey,
            final boolean defaultKycStatus,
            final boolean defaultFreezeStatus) {
        // Given
        final var treasuryEntity = accountPersistWithBalance(CREATE_TOKEN_VALUE);
        final var contract = testWeb3jService.deploy(NestedCalls::deploy);
        final var tokenInfo = getHederaToken(
                TokenTypeEnum.NON_FUNGIBLE_UNIQUE, withKeys, inheritKey, defaultFreezeStatus, treasuryEntity);
        tokenInfo.freezeDefault = defaultFreezeStatus;

        final var functionCall =
                contract.call_createNFTAndGetIsTokenAndGetDefaultFreezeStatusAndGetDefaultKycStatus(tokenInfo);
        final var callData =
                Bytes.fromHexString(functionCall.encodeFunctionCall()).toArray();
        final var expectedResult = getExpectedResultFromBooleans(defaultKycStatus, defaultFreezeStatus, true);
        final var expectedResultBytes = expectedResult.getBytes();

        final var transactionIdOrHash =
                setUpEthereumTransactionWithSenderBalance(contract, callData, CREATE_TOKEN_VALUE, expectedResultBytes);
        final OpcodeTracerOptions options = new OpcodeTracerOptions();

        // When
        final var opcodesResponse = opcodeService.processOpcodeCall(transactionIdOrHash, options);

        // Then
        verifyOpcodesResponseWithExpectedReturnValue(opcodesResponse, options, HEX_PREFIX + expectedResult);
    }

    private byte getByteFromBoolean(final boolean bool) {
        return bool ? (byte) 1 : (byte) 0;
    }

    private String getExpectedResultFromBooleans(Boolean... booleans) {
        StringBuilder result = new StringBuilder();
        for (Boolean booleanValue : booleans) {
            final var byteValue = getByteFromBoolean(booleanValue);
            result.append(DomainUtils.bytesToHex(DomainUtils.leftPadBytes(new byte[] {byteValue}, 32)));
        }
        return result.toString();
    }

    private HederaToken getHederaToken(
            final TokenTypeEnum tokenType, final Entity treasuryEntity, final Entity autoRenewAccountEntity) {
        return new HederaToken(
                "name",
                "symbol",
                toAddress(treasuryEntity.getId()).toHexString(),
                "memo",
                tokenType == TokenTypeEnum.FUNGIBLE_COMMON ? Boolean.FALSE : Boolean.TRUE,
                BigInteger.valueOf(10L),
                Boolean.TRUE,
                List.of(),
                getTokenExpiry(autoRenewAccountEntity));
    }

    private Entity accountPersist() {
        return domainBuilder.entity().customize(a -> a.evmAddress(null)).persist();
    }

    private Entity accountPersistWithBalance(final long balance) {
        final var entity = domainBuilder
                .entity()
                .customize(e -> e.evmAddress(null).balance(balance))
                .persist();

        domainBuilder
                .accountBalance()
                .customize(ab -> ab.id(new AccountBalance.Id(entity.getCreatedTimestamp(), EntityId.of(2)))
                        .balance(balance))
                .persist();
        domainBuilder
                .accountBalance()
                .customize(ab -> ab.id(new AccountBalance.Id(entity.getCreatedTimestamp(), entity.toEntityId()))
                        .balance(balance))
                .persist();

        return entity;
    }

    private Token fungibleTokenPersist() {
        final var tokenEntity =
                domainBuilder.entity().customize(e -> e.type(TOKEN)).persist();

        return domainBuilder
                .token()
                .customize(t -> t.tokenId(tokenEntity.getId()).type(TokenTypeEnum.FUNGIBLE_COMMON))
                .persist();
    }

    private Token fungibleTokenPersist(final Entity autoRenewAccount) {
        final var tokenEntity = domainBuilder
                .entity()
                .customize(e -> e.type(TOKEN).autoRenewAccountId(autoRenewAccount.getId()))
                .persist();

        return domainBuilder
                .token()
                .customize(t -> t.tokenId(tokenEntity.getId())
                        .type(TokenTypeEnum.FUNGIBLE_COMMON)
                        .treasuryAccountId(autoRenewAccount.toEntityId()))
                .persist();
    }

    private Token nftPersist(final EntityId treasuryEntityId) {
        final var tokenEntity = domainBuilder
                .entity()
                .customize(e -> e.type(TOKEN).autoRenewAccountId(treasuryEntityId.getId()))
                .persist();

        final var token = domainBuilder
                .token()
                .customize(t -> t.tokenId(tokenEntity.getId())
                        .type(TokenTypeEnum.NON_FUNGIBLE_UNIQUE)
                        .treasuryAccountId(treasuryEntityId))
                .persist();

        domainBuilder
                .nft()
                .customize(n -> n.tokenId(tokenEntity.getId()).serialNumber(1L))
                .persist();
        return token;
    }

    private TransactionIdOrHashParameter setUp(
            final TransactionType transactionType,
            final Contract contract,
            final byte[] callData,
            final boolean persistTransaction,
            final boolean persistContractResult,
            final EntityId senderEntityId,
            final long consensusTimestamp) {
        return setUpForSuccessWithExpectedResult(
                transactionType,
                contract,
                callData,
                persistTransaction,
                persistContractResult,
                senderEntityId,
                null,
                consensusTimestamp);
    }

    private TransactionIdOrHashParameter setUpEthereumTransaction(
            final Contract contract, final byte[] callData, final byte[] expectedResult) {
        return setUpEthereumTransactionWithSenderBalance(contract, callData, 0, expectedResult);
    }

    private TransactionIdOrHashParameter setUpEthereumTransactionWithSenderBalance(
            final Contract contract, final byte[] callData, final long senderBalance, final byte[] expectedResult) {
        final var senderEntity = accountPersistWithBalance(senderBalance);
        return setUpForSuccessWithExpectedResultAndBalance(
                ETHEREUMTRANSACTION,
                contract,
                callData,
                true,
                true,
                senderEntity.toEntityId(),
                senderBalance,
                expectedResult,
                senderEntity.getCreatedTimestamp() + 1);
    }

    private TransactionIdOrHashParameter setUpForSuccessWithExpectedResult(
            final TransactionType transactionType,
            final Contract contract,
            final byte[] callData,
            final boolean persistTransaction,
            final boolean persistContractResult,
            final EntityId senderEntityId,
            final byte[] expectedResult,
            final long consensusTimestamp) {
        return setUpForSuccessWithExpectedResultAndBalance(
                transactionType,
                contract,
                callData,
                persistTransaction,
                persistContractResult,
                senderEntityId,
                0,
                expectedResult,
                consensusTimestamp);
    }

    private TransactionIdOrHashParameter setUpForSuccessWithExpectedResultAndBalance(
            final TransactionType transactionType,
            final Contract contract,
            final byte[] callData,
            final boolean persistTransaction,
            final boolean persistContractResult,
            final EntityId senderEntityId,
            final long senderBalance,
            final byte[] expectedResult,
            final long consensusTimestamp) {

        final var ethHash = domainBuilder.bytes(32);
        final var contractAddress = Address.fromHexString(contract.getContractAddress());
        final var contractEntityId = entityIdFromEvmAddress(contractAddress);

        final var transaction = persistTransaction(
                consensusTimestamp, contractEntityId, senderEntityId, transactionType, persistTransaction);

        final EthereumTransaction ethTransaction;
        if (transactionType == ETHEREUMTRANSACTION) {
            ethTransaction = persistEthereumTransaction(
                    callData,
                    consensusTimestamp,
                    ethHash,
                    senderEntityId,
                    contract.getContractAddress(),
                    persistTransaction,
                    senderBalance);
        } else {
            ethTransaction = null;
        }
        final var contractResult = createContractResult(
                consensusTimestamp,
                contractEntityId,
                callData,
                senderEntityId,
                transaction,
                persistContractResult,
                senderBalance);

        persistContractActionsWithExpectedResult(
                senderEntityId,
                consensusTimestamp,
                contractEntityId,
                contract.getContractAddress(),
                expectedResult,
                senderBalance);

        if (persistTransaction) {
            persistContractTransactionHash(
                    consensusTimestamp, contractEntityId, ethHash, senderEntityId, contractResult);
        }

        if (ethTransaction != null) {
            return new TransactionHashParameter(Bytes.of(ethTransaction.getHash()));
        } else {
            return new TransactionIdParameter(transaction.getPayerAccountId(), instant(transaction.getValidStartNs()));
        }
    }

    private Transaction persistTransaction(
            final long consensusTimestamp,
            final EntityId contractEntityId,
            final EntityId senderEntityId,
            final TransactionType transactionType,
            final boolean persistTransaction) {
        final var validStartNs = consensusTimestamp - 1;

        final var transactionBuilder = domainBuilder.transaction().customize(transaction -> transaction
                .consensusTimestamp(consensusTimestamp)
                .entityId(contractEntityId)
                .payerAccountId(senderEntityId)
                .type(transactionType.getProtoId())
                .validStartNs(validStartNs));

        return persistTransaction ? transactionBuilder.persist() : transactionBuilder.get();
    }

    private EthereumTransaction persistEthereumTransaction(
            final byte[] callData,
            final long consensusTimestamp,
            final byte[] ethHash,
            final EntityId senderEntityId,
            final String contractAddress,
            final boolean persistTransaction,
            final long value) {
        final var ethTransactionBuilder = domainBuilder
                .ethereumTransaction(false)
                .customize(ethereumTransaction -> ethereumTransaction
                        .callData(callData)
                        .consensusTimestamp(consensusTimestamp)
                        .gasLimit(TRANSACTION_GAS_LIMIT)
                        .hash(ethHash)
                        .payerAccountId(senderEntityId)
                        .toAddress(Address.fromHexString(contractAddress).toArray())
                        .value(
                                value > 0
                                        ? BigInteger.valueOf(value).toByteArray()
                                        : BigInteger.valueOf(AMOUNT).toByteArray()));
        return persistTransaction ? ethTransactionBuilder.persist() : ethTransactionBuilder.get();
    }

    private ContractResult createContractResult(
            final long consensusTimestamp,
            final EntityId contractEntityId,
            final byte[] callData,
            final EntityId senderEntityId,
            final Transaction transaction,
            final boolean persistContractResult,
            final long value) {
        final var contractResultBuilder = domainBuilder.contractResult().customize(contractResult -> contractResult
                .amount(value > 0 ? value : AMOUNT)
                .consensusTimestamp(consensusTimestamp)
                .contractId(contractEntityId.getId())
                .functionParameters(callData)
                .gasLimit(TRANSACTION_GAS_LIMIT)
                .senderId(senderEntityId)
                .transactionHash(transaction.getTransactionHash()));
        return persistContractResult ? contractResultBuilder.persist() : contractResultBuilder.get();
    }

    private void persistContractActionsWithExpectedResult(
            final EntityId senderEntityId,
            final long consensusTimestamp,
            final EntityId contractEntityId,
            final String contractAddress,
            final byte[] expectedResult,
            final long value) {
        domainBuilder
                .contractAction()
                .customize(contractAction -> contractAction
                        .caller(senderEntityId)
                        .callerType(EntityType.ACCOUNT)
                        .consensusTimestamp(consensusTimestamp)
                        .payerAccountId(senderEntityId)
                        .recipientContract(contractEntityId)
                        .recipientAddress(contractAddress.getBytes())
                        .gas(TRANSACTION_GAS_LIMIT)
                        .resultData(expectedResult)
                        .resultDataType(OUTPUT.getNumber())
                        .value(value > 0 ? value : AMOUNT))
                .persist();
    }

    private ContractTransactionHash persistContractTransactionHash(
            final long consensusTimestamp,
            final EntityId contractEntityId,
            final byte[] ethHash,
            final EntityId senderEntityId,
            final ContractResult contractResult) {
        return domainBuilder
                .contractTransactionHash()
                .customize(contractTransactionHash -> contractTransactionHash
                        .consensusTimestamp(consensusTimestamp)
                        .entityId(contractEntityId.getId())
                        .hash(ethHash)
                        .payerAccountId(senderEntityId.getId())
                        .transactionResult(contractResult.getTransactionResult()))
                .persist();
    }

    private Address entityAddress(Entity entity) {
        if (entity == null) {
            return Address.ZERO;
        }
        if (entity.getEvmAddress() != null && entity.getEvmAddress().length == EVM_ADDRESS_LENGTH) {
            return Address.wrap(Bytes.wrap(entity.getEvmAddress()));
        }
        if (entity.getAlias() != null && entity.getAlias().length == EVM_ADDRESS_LENGTH) {
            return Address.wrap(Bytes.wrap(entity.getAlias()));
        }
        return toAddress(entity.toEntityId());
    }

    private OpcodesResponse expectedOpcodesResponse(
            final HederaEvmTransactionProcessingResult result, final List<Opcode> opcodes) {
        return new OpcodesResponse()
                .address(result.getRecipient()
                        .flatMap(address -> entityDatabaseAccessor.get(address, Optional.empty()))
                        .map(this::entityAddress)
                        .map(Address::toHexString)
                        .orElse(Address.ZERO.toHexString()))
                .contractId(result.getRecipient()
                        .flatMap(address -> entityDatabaseAccessor.get(address, Optional.empty()))
                        .map(Entity::toEntityId)
                        .map(EntityId::toString)
                        .orElse(null))
                .failed(!result.isSuccessful())
                .gas(result.getGasUsed())
                .opcodes(opcodes.stream()
                        .map(opcode -> new com.hedera.mirror.rest.model.Opcode()
                                .depth(opcode.depth())
                                .gas(opcode.gas())
                                .gasCost(opcode.gasCost())
                                .op(opcode.op())
                                .pc(opcode.pc())
                                .reason(opcode.reason())
                                .stack(opcode.stack().stream()
                                        .map(Bytes::toHexString)
                                        .toList())
                                .memory(opcode.memory().stream()
                                        .map(Bytes::toHexString)
                                        .toList())
                                .storage(opcode.storage().entrySet().stream()
                                        .collect(Collectors.toMap(
                                                entry -> entry.getKey().toHexString(),
                                                entry -> entry.getValue().toHexString()))))
                        .toList())
                .returnValue(Optional.ofNullable(result.getOutput())
                        .map(Bytes::toHexString)
                        .orElse(Bytes.EMPTY.toHexString()));
    }

    // TODO: make it csv?
    static Stream<Arguments> tracerOptions() {
        return Stream.of(
                        new OpcodeTracerOptions(true, true, true),
                        new OpcodeTracerOptions(false, true, true),
                        new OpcodeTracerOptions(true, false, true),
                        new OpcodeTracerOptions(true, true, false),
                        new OpcodeTracerOptions(false, false, true),
                        new OpcodeTracerOptions(false, true, false),
                        new OpcodeTracerOptions(true, false, false),
                        new OpcodeTracerOptions(false, false, false))
                .map(Arguments::of);
    }

    private void verifyOpcodesResponse(final OpcodesResponse opcodesResponse, final OpcodeTracerOptions options) {
        assertThat(opcodesResponse).isEqualTo(expectedOpcodesResponse(resultCaptor, contextCaptor.getOpcodes()));
        assertThat(gasCaptor.getValue()).isEqualTo(TRANSACTION_GAS_LIMIT);
        assertThat(contextCaptor.getOpcodeTracerOptions()).isEqualTo(options);
    }

    private void verifyOpcodesResponseWithExpectedReturnValue(
            final OpcodesResponse opcodesResponse,
            final OpcodeTracerOptions options,
            final String expectedReturnValue) {
        assertThat(opcodesResponse).isEqualTo(expectedOpcodesResponse(resultCaptor, contextCaptor.getOpcodes()));
        assertThat(gasCaptor.getValue()).isEqualTo(TRANSACTION_GAS_LIMIT);
        assertThat(contextCaptor.getOpcodeTracerOptions()).isEqualTo(options);

        assertThat(opcodesResponse.getFailed()).isFalse();
        assertThat(opcodesResponse.getReturnValue()).isEqualTo(expectedReturnValue);
    }
}
