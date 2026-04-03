package pt.depchain.hotstuff;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import pt.depchain.communication.Block;
import pt.depchain.communication.State;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.PrintStream;
import java.io.FileReader;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.*;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.fluent.EVMExecutor;
import org.hyperledger.besu.evm.fluent.SimpleWorld;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.operation.Operation.OperationResult;
import org.hyperledger.besu.evm.tracing.OperationTracer;
import org.hyperledger.besu.evm.tracing.StandardJsonTracer;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;
import org.w3c.dom.Node;
import org.web3j.crypto.Hash;
import org.web3j.utils.Numeric;
import org.hyperledger.besu.datatypes.Address;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

public class AccountOperations {
    private SimpleWorld simpleWorld = new SimpleWorld();
    Map<String, MutableAccount> userAccounts = new HashMap<>();

    String deploymentBytecode;
    String runtimeBytecode;
    Address contractAddress;

    String allowance;
    String balanceOf;
    String transfer;
    String transferFrom;
    String increaseAllowance;
    String decreaseAllowance;

    public AccountOperations() {
        this.contractAddress = Address.fromHexString("1234567891234567891234567891234567891234");

        try {
            // Option 1: Move up one level to the sibling ERC20 folder (../ERC20/...)
            Path parentErc20 = Path.of("..", "ERC20", "keccak_256.json");
            // Option 2: Look in a local ERC20 folder (ERC20/...)
            Path localErc20 = Path.of("ERC20", "keccak_256.json");
            // Option 3: Current directory
            Path currentDir = Path.of("keccak_256.json");

            Path jsonPath;
            if (Files.exists(parentErc20)) {
                jsonPath = parentErc20;
            } else if (Files.exists(localErc20)) {
                jsonPath = localErc20;
            } else {
                jsonPath = currentDir;
            }

            // Read and parse
            String content = Files.readString(jsonPath);
            JsonObject jsonObject = JsonParser.parseString(content).getAsJsonObject();

            // Assigning values from JSON
            this.deploymentBytecode = jsonObject.get("EVM Deployment Bytecode").getAsString();
            this.runtimeBytecode = jsonObject.get("EVM Runtime Bytecode").getAsString();
            this.allowance = jsonObject.get("allowance(address,address)").getAsString();
            this.balanceOf = jsonObject.get("balanceOf(address)").getAsString();
            this.transfer = jsonObject.get("transfer(address,uint256)").getAsString();
            this.transferFrom = jsonObject.get("transferFrom(address,address,uint256)").getAsString();
            this.increaseAllowance = jsonObject.get("increaseAllowance(address,uint256)").getAsString();
            this.decreaseAllowance = jsonObject.get("decreaseAllowance(address,uint256)").getAsString();

        } catch (Exception e) {
            throw new RuntimeException("Failed to load ERC20 selectors from keccak_256.json at specified paths", e);
        }


    }

    public void main(String[] args) {
        // // === Step 1: Create EOAs ===
        // Address client1Addr = Address.fromHexString("1111111111111111111111111111111111111111");
        // Address client2Addr = Address.fromHexString("2222222222222222222222222222222222222222");
        // Address client3Addr = Address.fromHexString("3333333333333333333333333333333333333333");


        // createAccount(client1Addr, BigInteger.valueOf(10000000));
        // createAccount(client2Addr, BigInteger.valueOf(10000000));
        // createAccount(client3Addr, BigInteger.valueOf(10000000));
        // contractAddress = Address.fromHexString("1234567891234567891234567891234567891234");
        // simpleWorld.createAccount(contractAddress, 0, Wei.ZERO);

        // // === Step 2: Deploy contract (constructor runs here) ===
        // deployContract(client1Addr, contractAddress); // deployer = client1, owner = treasury

        // MutableAccount account = (MutableAccount) simpleWorld.get(contractAddress);
        // System.out.println(account.getCode().size());

        // // After deploy
        // System.out.println("After deploy:");
        // System.out.println("Contract address: " + callBalanceOf(contractAddress, contractAddress));
        // System.out.println("Client1: " + callBalanceOf(client1Addr, client1Addr));
        // System.out.println("Client2: " + callBalanceOf(client2Addr, client2Addr));
        // System.out.println("Client3: " + callBalanceOf(client3Addr, client3Addr));

        // String data = balanceOf + padAddress(client1Addr);
        // System.out.println(genericCall(client1Addr, data, true, client3Addr));
        // BigInteger value = BigInteger.valueOf(20);
        // data = transfer + padAddress(client1Addr) + convertIntegerToHex256Bit(value.intValue());
        // genericCall(contractAddress, data, true, client3Addr);

        //String data = balanceOf + padAddress(client1Addr);

        // // // Distribute
        // transfer(contractAddress, client1Addr, BigInteger.valueOf(1000));
        // transfer(contractAddress, client2Addr, BigInteger.valueOf(1000));

        // // // Distribute
        // String data = transfer + padAddress(client1Addr) + convertIntegerToHex256Bit(BigInteger.valueOf(1000).intValue());
        // genericCall(contractAddress, data, true, client3Addr);
        // data = transfer + padAddress(client2Addr) + convertIntegerToHex256Bit(BigInteger.valueOf(1000).intValue());
        // genericCall(contractAddress, data, true, client3Addr);


        //After distribution
        // System.out.println("After distribution:");
        // System.out.println("Contract address: " + callBalanceOf(contractAddress, contractAddress));
        // System.out.println("Client1: " + callBalanceOf(client1Addr, client1Addr));
        // System.out.println("Client2: " + callBalanceOf(client2Addr, client2Addr));
        // System.out.println("Client3: " + callBalanceOf(client3Addr, client3Addr));

        // // === Step 4: Transfer tokens ===
        // System.out.println("\nTransferring 100 tokens from client1 to client2...\n");

        // transfer(contractAddress, client2Addr, BigInteger.valueOf(100));

        // // === Step 5: Check balances again ===
        // System.out.println("After transfer:");
        // System.out.println("Client1: " + callBalanceOf(client1Addr, client1Addr));
        // System.out.println("Client2: " + callBalanceOf(client2Addr, client2Addr));
    }

    public void createAccount(Address accountAddress, BigInteger initialBalance) {
        MutableAccount account = (MutableAccount) simpleWorld.getAccount(accountAddress);
        if (account == null) {
            account = (MutableAccount) simpleWorld.createAccount(accountAddress);
        }
        account.setBalance(Wei.of(initialBalance));
        userAccounts.put(accountAddress.toHexString(), account);
        System.out.println("Created account: " + accountAddress + " with balance: " + initialBalance);
    }

    public void initializeAccount(String accountAddress, State accountState) {
        String normalizedHex = normalizeAddressHex(accountAddress);
        BigInteger initialBalance = BigInteger.valueOf((long) accountState.getBalance());
        createAccount(Address.fromHexString(normalizedHex), initialBalance);
    }


    public String normalizeAddressHex(String rawKey) {
        if ("contractAccount_address".equals(rawKey)) {
            return "1234567891234567891234567891234567891234";
        }

        String hex = rawKey.startsWith("0x") ? rawKey.substring(2) : rawKey;

        if (hex.length() == 64) {
            return hex.substring(0, 40);
        }

        if (hex.length() == 40) {
            return hex;
        }

        throw new IllegalArgumentException("Invalid address key length in genesis state: " + rawKey);
    }

    
    public void deployContract(Address deployer, Address owner) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        PrintStream printStream = new PrintStream(outputStream);
        StandardJsonTracer tracer = new StandardJsonTracer(printStream, true, true, true, true);

        var executor = EVMExecutor.evm(EvmSpecVersion.CANCUN);
        executor.tracer(tracer);
        WorldUpdater updater = simpleWorld.updater();
        executor.worldUpdater(updater);
        executor.messageFrameType(MessageFrame.Type.CONTRACT_CREATION);
        executor.sender(deployer);

        Bytes initCode = Bytes.fromHexString(deploymentBytecode);
        Bytes constructorArgs = encodeAddress(owner);
        Bytes input = Bytes.concatenate(initCode, constructorArgs);

        executor.code(input);
        executor.callData(Bytes.EMPTY);
        executor.execute();

        Bytes runtimeCode = extractRuntimeCode(outputStream);

        // Extrair o storage final do trace (último frame antes do RETURN)
        Map<UInt256, UInt256> finalStorage = extractStorageFromTrace(outputStream);

        updater.commit();

        MutableAccount deployed = (MutableAccount) simpleWorld.get(contractAddress);
        deployed.setCode(runtimeCode);

        // Aplicar o storage manualmente
        for (Map.Entry<UInt256, UInt256> entry : finalStorage.entrySet()) {
            deployed.setStorageValue(entry.getKey(), entry.getValue());
        }

        System.out.println("Storage slot 3 (totalSupply): " + deployed.getStorageValue(UInt256.valueOf(3)));
        System.out.println("Code size: " + deployed.getCode().size());
        System.out.println("Contract deployed at: " + contractAddress);
    }

    private static Map<UInt256, UInt256> extractStorageFromTrace(ByteArrayOutputStream outputStream) {
        String[] lines = outputStream.toString().split("\\r?\\n");
        Map<UInt256, UInt256> storage = new HashMap<>();

        // Percorrer do fim para encontrar o último frame com storage
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;

            JsonObject obj = JsonParser.parseString(line).getAsJsonObject();

            if (obj.has("storage")) {
                JsonObject storageJson = obj.getAsJsonObject("storage");
                for (String key : storageJson.keySet()) {
                    UInt256 k = UInt256.fromHexString(key);
                    UInt256 v = UInt256.fromHexString(storageJson.get(key).getAsString());
                    storage.put(k, v);
                }
                break; // último frame com storage é suficiente
            }
        }
        return storage;
    }
    
    private Bytes encodeAddress(Address addr) {
        byte[] padded = new byte[32];
        byte[] raw = addr.toArray();

        System.arraycopy(raw, 0, padded, 12, 20);

        return Bytes.wrap(padded);
    }


    public int genericCall(Address senderAddress, String calldata, boolean leader, Address NodeAddress) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        PrintStream printStream = new PrintStream(outputStream);

        StandardJsonTracer tracer = new StandardJsonTracer(printStream, true, true, true, true);

        var executor = EVMExecutor.evm(EvmSpecVersion.CANCUN);

        executor.tracer(tracer);
        var updater = simpleWorld.updater();
        executor.worldUpdater(updater);

        executor.sender(senderAddress);
        executor.receiver(contractAddress);
        executor.code(simpleWorld.get(contractAddress).getCode());
        executor.callData(Bytes.fromHexString(calldata));

        executor.execute();


        updater.commit();

        // System.out.println("=== BALANCEOF TRACER ===");
        //System.out.println(outputStream.toString());
        // System.out.println("=== END OF BALANCEOF TRACER ===");
        long gasUsed = 1; //extractGasUsedFromTrace(outputStream, 1000);
        
        //System.out.println(outputStream.toString());
        // 4. If Leader, reward the NodeAddress account
        if (leader && NodeAddress != null) {
            // Get the mutable account for the leader
            var leaderAccount = updater.getOrCreate(NodeAddress);
            
            // Assuming a gas price of 1 (rewarding 1 Wei per gas used)
            Wei reward = Wei.of(gasUsed); 
            
            // Update the balance
            leaderAccount.setBalance(leaderAccount.getBalance().add(reward));
            
            //System.out.println("Leader " + NodeAddress + " rewarded with " + gasUsed + " Wei.");
        }

        System.out.println();
        return extractIntegerFromReturnData(outputStream);
    }

    public static long extractGasUsedFromTrace(ByteArrayOutputStream outputStream, long initialGas) {
        String[] lines = outputStream.toString().split("\\r?\\n");
        if (lines.length == 0) return 0;

        // The last line or second-to-last line usually contains the final state
        // We look for the "gas" field in the JSON
        try {
            JsonObject lastStep = JsonParser.parseString(lines[lines.length - 1]).getAsJsonObject();
            
            // Besu JsonTracer uses "gas" for remaining gas in hex (e.g., "0x...").
            String gasHex = lastStep.get("gas").getAsString();
            long gasRemaining = Long.decode(gasHex);
            
            return initialGas - gasRemaining;
        } catch (Exception e) {
            return 0;
        }
    }

    public static int extractIntegerFromReturnData(ByteArrayOutputStream byteArrayOutputStream) {
        String[] lines = byteArrayOutputStream.toString().split("\\r?\\n");
        JsonObject jsonObject = JsonParser.parseString(
            lines[lines.length - 1]
        ).getAsJsonObject();

        String memory = jsonObject.get("memory").getAsString();

        JsonArray stack = jsonObject.get("stack").getAsJsonArray();
        int offset = Integer.decode(stack.get(stack.size() - 1).getAsString());
        int size = Integer.decode(stack.get(stack.size() - 2).getAsString());

        String returnData = memory.substring(
            2 + offset * 2,
            2 + offset * 2 + size * 2
        );
        return Integer.decode("0x" + returnData);
    }

    private String padAddress(Address addr) {
        String hex = addr.toHexString().replace("0x", "");
        return "000000000000000000000000" + hex;
    }

    public static String convertIntegerToHex256Bit(int number) {
        BigInteger bigInt = BigInteger.valueOf(number);

        return String.format("%064x", bigInt);
    }

    public static Bytes extractRuntimeCode(ByteArrayOutputStream outputStream) {
        String[] lines = outputStream.toString().split("\\r?\\n");

        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;

            JsonObject obj = JsonParser.parseString(line).getAsJsonObject();

            if (obj.has("opName") && obj.get("opName").getAsString().equals("RETURN")) {
                String memory = obj.get("memory").getAsString();
                JsonArray stack = obj.getAsJsonArray("stack");

                int offset = Integer.decode(stack.get(stack.size() - 1).getAsString());
                int size = Integer.decode(stack.get(stack.size() - 2).getAsString());

                String hex = memory.substring(
                    2 + offset * 2,
                    2 + offset * 2 + size * 2
                );

                return Bytes.fromHexString(hex);
            }
        }

        throw new RuntimeException("No RETURN frame found in trace");
    }
}