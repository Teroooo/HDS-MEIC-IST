package pt.depchain.hotstuff;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
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
import org.hyperledger.besu.evm.tracing.StandardJsonTracer;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;
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

        // === Step 1: Create EOAs ===
        Address client1Addr = Address.fromHexString("1111111111111111111111111111111111111111");
        Address client2Addr = Address.fromHexString("2222222222222222222222222222222222222222");
        Address treasuryAddr = Address.fromHexString("3333333333333333333333333333333333333333");

        createAccount(client1Addr, BigInteger.valueOf(10000000));
        createAccount(client2Addr, BigInteger.valueOf(10000000));
        createAccount(treasuryAddr, BigInteger.valueOf(0));

        // === Step 2: Deploy contract (constructor runs here) ===
        deployContract(client1Addr, treasuryAddr); // deployer = client1, owner = treasury

        MutableAccount account = (MutableAccount) simpleWorld.get(contractAddress);
        System.out.println(account.getCode().size());

        // After deploy
        System.out.println("After deploy:");
        // System.out.println("Treasury: " + callBalanceOf(treasuryAddr, treasuryAddr));
        // System.out.println("Client1: " + callBalanceOf(client1Addr, client1Addr));
        // System.out.println("Client2: " + callBalanceOf(client2Addr, client2Addr));

        // // Distribute
        // transfer(treasuryAddr, client1Addr, BigInteger.valueOf(1000));
        // transfer(treasuryAddr, client2Addr, BigInteger.valueOf(1000));

        // // After distribution
        // System.out.println("After distribution:");
        // System.out.println("Treasury: " + callBalanceOf(treasuryAddr, treasuryAddr));
        // System.out.println("Client1: " + callBalanceOf(client1Addr, client1Addr));
        // System.out.println("Client2: " + callBalanceOf(client2Addr, client2Addr));

        // // === Step 4: Transfer tokens ===
        // System.out.println("\nTransferring 100 tokens from client1 to client2...\n");

        // transfer(treasuryAddr, client2Addr, BigInteger.valueOf(100));

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
    }


    public void deployContract(Address deployer, Address owner) {
        contractAddress = Address.fromHexString("1234567891234567891234567891234567891234");
        simpleWorld.createAccount(contractAddress, 0, Wei.ZERO);

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

        System.out.println(constructorArgs.toHexString());
        executor.code(input);
        executor.callData(Bytes.EMPTY);
        
        executor.execute();
        MutableAccount acc = (MutableAccount) simpleWorld.get(contractAddress);
        System.out.println("Code before commit: " + acc.getCode());
        
        updater.commit();
        acc = (MutableAccount) simpleWorld.get(contractAddress);
        System.out.println("Code after commit: " + acc.getCode());
        //Bytes runtimeCode = extractRuntimeCode(outputStream);
        //account.setCode(runtimeCode);
        
        //System.out.println(outputStream);
        System.out.println("Contract deployed at: " + contractAddress);
        // System.out.println("Runtime code length: " + (account.getCode() == null ? 0 : account.getCode().size()));
    }
    
    private Bytes encodeAddress(Address addr) {
        byte[] padded = new byte[32];
        byte[] raw = addr.toArray();

        System.arraycopy(raw, 0, padded, 12, 20);

        return Bytes.wrap(padded);
    }

    public int callBalanceOf(Address caller, Address target) {

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        PrintStream printStream = new PrintStream(outputStream);

        StandardJsonTracer tracer = new StandardJsonTracer(printStream, true, true, true, true);

        var executor = EVMExecutor.evm(EvmSpecVersion.CANCUN);

        executor.tracer(tracer);
        var updater = simpleWorld.updater();
        executor.worldUpdater(updater);

        Bytes calldata = Bytes.fromHexString(
            balanceOf + padAddress(target)
        );

        executor.sender(caller);
        executor.receiver(contractAddress);
        executor.code(simpleWorld.get(contractAddress).getCode());
        executor.callData(calldata);

        executor.execute();


        updater.commit();

        // System.out.println("=== BALANCEOF TRACER ===");
        //System.out.println(outputStream.toString());
        // System.out.println("=== END OF BALANCEOF TRACER ===");

        return extractIntegerFromReturnData(outputStream);
    }

    public void transfer(Address sender, Address to, BigInteger value) {

        String data =
            transfer +
            padAddress(to) +
            convertIntegerToHex256Bit(value.intValue());

        var executor = EVMExecutor.evm(EvmSpecVersion.CANCUN);

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        PrintStream printStream = new PrintStream(outputStream);

        StandardJsonTracer tracer = new StandardJsonTracer(printStream, true, true, true, true);

        executor.tracer(tracer);
        var updater = simpleWorld.updater();
        executor.worldUpdater(updater);

        executor.code(simpleWorld.get(contractAddress).getCode());
        executor.sender(sender);
        executor.receiver(contractAddress);
        executor.callData(Bytes.fromHexString(data));

        executor.execute();

        updater.commit();

        // System.out.println("=== TRANSFER TRACER ===");
        // System.out.println(outputStream.toString());
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