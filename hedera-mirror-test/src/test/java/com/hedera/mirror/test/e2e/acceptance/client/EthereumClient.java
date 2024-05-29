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

package com.hedera.mirror.test.e2e.acceptance.client;

import com.esaulpaugh.headlong.abi.Tuple;
import com.esaulpaugh.headlong.abi.TupleType;
import com.esaulpaugh.headlong.util.Integers;
import com.hedera.hashgraph.sdk.ContractExecuteTransaction;
import com.hedera.hashgraph.sdk.ContractFunctionParameters;
import com.hedera.hashgraph.sdk.ContractFunctionResult;
import com.hedera.hashgraph.sdk.ContractId;
import com.hedera.hashgraph.sdk.EthereumTransaction;
import com.hedera.hashgraph.sdk.FileId;
import com.hedera.hashgraph.sdk.Hbar;
import com.hedera.hashgraph.sdk.PrivateKey;
import com.hedera.hashgraph.sdk.TransactionRecord;
import com.hedera.mirror.test.e2e.acceptance.config.AcceptanceTestProperties;
import com.hedera.mirror.test.e2e.acceptance.response.NetworkTransactionResponse;
import com.hedera.mirror.test.e2e.acceptance.util.ethereum.EthTxData;
import com.hedera.mirror.test.e2e.acceptance.util.ethereum.EthTxSigs;
import jakarta.inject.Named;
import java.math.BigInteger;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.tuweni.bytes.Bytes;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.retry.support.RetryTemplate;

@Named
public class EthereumClient extends AbstractNetworkClient {
    @Autowired
    private AcceptanceTestProperties acceptanceTestProperties;

    private final Map<PrivateKey, Integer> accountNonce = new ConcurrentHashMap<>();

    public EthereumClient(SDKClient sdkClient, RetryTemplate retryTemplate) {
        super(sdkClient, retryTemplate);
    }

    @Override
    public void clean() {
        // Contracts created by ethereum transactions are immutable
        log.info("Can't delete contracts created by ethereum transactions");
    }

    private final TupleType LONG_TUPLE = TupleType.parse("(int64)");

    protected byte[] gasLongToBytes(final Long gas) {
        return Bytes.wrap(LONG_TUPLE.encode(Tuple.of(gas)).array()).toArray();
    }

    public static final BigInteger WEIBARS_TO_TINYBARS = BigInteger.valueOf(10_000_000_000L);
    private final BigInteger maxFeePerGas = WEIBARS_TO_TINYBARS.multiply(BigInteger.valueOf(50L));
    private final BigInteger gasPrice = WEIBARS_TO_TINYBARS.multiply(BigInteger.valueOf(50L));

    public NetworkTransactionResponse createContract(
            PrivateKey signerKey, FileId fileId, String fileContents, long gas, Hbar payableAmount) {

        int nonce = getNonce(signerKey);
        byte[] chainId = Integers.toBytes(acceptanceTestProperties.getNetwork().getChainId());
        byte[] maxPriorityGas = gasLongToBytes(20_000L);
        byte[] maxGas = gasLongToBytes(maxFeePerGas.longValueExact());
        BigInteger value = payableAmount != null
                ? WEIBARS_TO_TINYBARS.multiply(BigInteger.valueOf(payableAmount.toTinybars()))
                : BigInteger.ZERO;

        byte[] callData = Bytes.fromHexString(fileContents).toArray();

        var ethTxData = new EthTxData(
                EthTxData.EthTransactionType.LEGACY_ETHEREUM,
                chainId,
                nonce,
                gasLongToBytes(gasPrice.longValueExact()),
                maxPriorityGas,
                maxGas,
                gas, // gasLimit
                ArrayUtils.EMPTY_BYTE_ARRAY, // to
                value, // value
                callData,
                ArrayUtils.EMPTY_BYTE_ARRAY, // accessList
                0,
                null,
                null,
                null);

        var signedEthTxData = EthTxSigs.signMessage(ethTxData, signerKey);
        signedEthTxData = signedEthTxData.replaceCallData(new byte[] {});

        EthereumTransaction ethereumTransaction = new EthereumTransaction()
                .setCallDataFileId(fileId)
                .setMaxGasAllowanceHbar(Hbar.from(100L))
                .setEthereumData(signedEthTxData.encodeTx());

        var memo = getMemo("Create contract");

        var response = executeTransactionAndRetrieveReceipt(ethereumTransaction, null, null);
        var contractId = response.getReceipt().contractId;
        log.info("Created new contract {} with memo '{}' via {}", contractId, memo, response.getTransactionId());

        TransactionRecord transactionRecord = getTransactionRecord(response.getTransactionId());
        logContractFunctionResult("constructor", transactionRecord.contractFunctionResult);
        return response;
    }

    public ContractClient.ExecuteContractResult executeContract(
            PrivateKey signerKey,
            ContractId contractId,
            long gas,
            String functionName,
            ContractFunctionParameters functionParameters,
            Hbar payableAmount,
            EthTxData.EthTransactionType type) {

        int nonce = getNonce(signerKey);
        byte[] chainId = Integers.toBytes(acceptanceTestProperties.getNetwork().getChainId());
        byte[] maxPriorityGas = gasLongToBytes(20_000L);
        byte[] maxGas = gasLongToBytes(maxFeePerGas.longValueExact());
        final var address = contractId.toSolidityAddress();
        final var addressBytes = Bytes.fromHexString(address.startsWith("0x") ? address : "0x" + address);
        byte[] to = addressBytes.toArray();
        var parameters = functionParameters != null ? functionParameters : new ContractFunctionParameters();
        byte[] callData = new ContractExecuteTransaction()
                .setFunction(functionName, parameters)
                .getFunctionParameters()
                .toByteArray();

        BigInteger value = payableAmount != null
                ? WEIBARS_TO_TINYBARS.multiply(BigInteger.valueOf(payableAmount.toTinybars()))
                : BigInteger.ZERO;

        var ethTxData = new EthTxData(
                type,
                chainId,
                nonce,
                gasLongToBytes(gasPrice.longValueExact()),
                maxPriorityGas,
                maxGas,
                gas, // gasLimit
                to, // to
                value, // value
                callData,
                ArrayUtils.EMPTY_BYTE_ARRAY, // accessList
                0,
                null,
                null,
                null);

        var signedEthTxData = EthTxSigs.signMessage(ethTxData, signerKey);
        EthereumTransaction ethereumTransaction = new EthereumTransaction()
                .setMaxGasAllowanceHbar(Hbar.from(100L))
                .setEthereumData(signedEthTxData.encodeTx());

        var response = executeTransactionAndRetrieveReceipt(ethereumTransaction, null, null);

        TransactionRecord transactionRecord = getTransactionRecord(response.getTransactionId());
        logContractFunctionResult(functionName, transactionRecord.contractFunctionResult);

        log.info("Called contract {} function {} via {}", contractId, functionName, response.getTransactionId());
        return new ContractClient.ExecuteContractResult(transactionRecord.contractFunctionResult, response);
    }

    private void logContractFunctionResult(String functionName, ContractFunctionResult contractFunctionResult) {
        if (contractFunctionResult == null) {
            return;
        }

        log.trace(
                "ContractFunctionResult for function {}, contractId: {}, gasUsed: {}, logCount: {}",
                functionName,
                contractFunctionResult.contractId,
                contractFunctionResult.gasUsed,
                contractFunctionResult.logs.size());
    }

    private Integer getNonce(PrivateKey accountKey) {
        return accountNonce.merge(accountKey, 1, Math::addExact) - 1;
    }
}
