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
import org.hyperledger.besu.evm.tracing.StandardJsonTracer;
import org.web3j.crypto.Hash;
import org.web3j.utils.Numeric;
import org.hyperledger.besu.datatypes.Address;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;

public class AccountOperations {
    private SimpleWorld simpleWorld = new SimpleWorld();
    MutableAccount contractAccount;
    Map<String, MutableAccount> userAccounts = new HashMap<>();

    String bytecode;
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
            this.bytecode = jsonObject.get("EVM Bytecode").getAsString();
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
        String client1 = "client1";
        String client2 = "client2";
        createAccount(client1, BigInteger.valueOf(10000000));
        createContractAccount("contract1", BigInteger.valueOf(0));
        System.out.println("Balance of " + client1 + ": " + balance(client1, client1));
        System.out.println("Balance of contract1: " + balance(client1, "contract1"));
        System.out.println("Allowance of " + client1 + ": " + allowance(client1, client1, "contract1"));
    }

    public void createAccount(String address, BigInteger initialBalance) {

        Address accountAddress = getAddressFromString(address);
        MutableAccount account = (MutableAccount) simpleWorld.getAccount(accountAddress);
        if (account == null) {
            account = (MutableAccount) simpleWorld.createAccount(accountAddress);
        }
        account.setBalance(Wei.of(initialBalance));
  
        System.out.println("Sender Account");
        System.out.println("  Address: " + account.getAddress());
        System.out.println("  Balance: " + account.getBalance());
        System.out.println("  Nonce: " + account.getNonce());
        System.out.println();
        userAccounts.put(address, account);
    }

    public void createContractAccount(String address, BigInteger initialBalance) {
        Address accountAddress = getAddressFromString(address);
        if (contractAccount == null || !contractAccount.getAddress().equals(accountAddress)) {
            contractAccount = (MutableAccount) simpleWorld.createAccount(accountAddress);
        }
        contractAccount.setBalance(Wei.of(initialBalance));
        // 1. ATTACH THE CODE (Critical: Without this, it returns no data)
        contractAccount.setCode(Bytes.fromHexString(bytecode));
        
        // 2. SEED THE TOTAL SUPPLY (Slot 0)
        // Solidity maps the first variable 'totalSupply' to Slot 0
        contractAccount.setStorageValue(UInt256.valueOf(0), UInt256.valueOf(100000000));

        // 3. SEED CLIENT1 BALANCE (Mapping)
        // You need to find the specific slot for balances[client1]
        seedBalance("client1", 10000000);
        System.out.println("Contract Account");
        System.out.println("  Address: " + contractAccount.getAddress());
        System.out.println("  Balance: " + contractAccount.getBalance());
        System.out.println("  Nonce: " + contractAccount.getNonce());
        System.out.println("  Storage:");
        System.out.println(
            "    Slot 0: " +
                simpleWorld
                    .get(accountAddress)
                    .getStorageValue(UInt256.valueOf(0))
        );
                String paddedAddress = padHexStringTo256Bit(
            accountAddress.toHexString()
        );
        String stateVariableIndex = convertIntegerToHex256Bit(1);
        String storageSlotMapping = Numeric.toHexStringNoPrefix(
            Hash.sha3(
                Numeric.hexStringToByteArray(paddedAddress + stateVariableIndex)
            )
        );
        System.out.println(
            "    Slot SHA3[msg.sender||1] (mapping): " +
                simpleWorld
                    .get(accountAddress)
                    .getStorageValue(UInt256.fromHexString(storageSlotMapping))
        );
        System.out.println();
    }



    public int balance(String sender, String balanceAddress) {
        Address senderAddress = getAddressFromString(sender);
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        PrintStream printStream = new PrintStream(byteArrayOutputStream);
        StandardJsonTracer tracer = new StandardJsonTracer(
            printStream,
            true,
            true,
            true,
            true
        );

        var executor = EVMExecutor.evm(EvmSpecVersion.CANCUN);
        executor.tracer(tracer);
        executor.code(Bytes.fromHexString(bytecode));
        executor.sender(senderAddress);
        executor.receiver(contractAccount.getAddress());
        executor.worldUpdater(simpleWorld.updater());
        executor.commitWorldState();

        Address targetAddr = getAddressFromString(balanceAddress);

        // 3. PAD ADDRESS TO 32 BYTES (64 chars)
        // We take the hex of the address and add 24 leading zeros
        String cleanAddrHex = targetAddr.toHexString().replace("0x", "");
        String paddedAddr = "000000000000000000000000" + cleanAddrHex;

        // 4. CONSTRUCT CALLDATA
        // balanceOf selector (8 chars) + padded address (64 chars) = 72 chars
        String finalHex = balanceOf + paddedAddr;
        System.out.println("Final Call Data for balanceOf: " + finalHex.length());
        // 5. EXECUTE
        executor.callData(Bytes.fromHexString(finalHex));

        // Instead of just .execute(), use the underlying message call if you need the Status
        var initialFrame = executor.execute(); 

        // To see if it "worked" without the result object:
        if (initialFrame.isEmpty() && !bytecode.isEmpty()) {
            System.out.println("Warning: Contract returned no data. It might have REVERTED.");
        }
        int balance = extractIntegerFromReturnData(byteArrayOutputStream);
        System.out.println(
            "Output of 'balanceOf(" + sender + ", " + balanceAddress + ")': " + Integer.toString(balance)
        );
        return balance;
    }

    public int allowance(String sender, String owner, String spender) {
        Address senderAddress = getAddressFromString(sender);
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        PrintStream printStream = new PrintStream(byteArrayOutputStream);
        StandardJsonTracer tracer = new StandardJsonTracer(
            printStream,
            true,
            true,
            true,
            true
        );

        var executor = EVMExecutor.evm(EvmSpecVersion.CANCUN);
        executor.tracer(tracer);
        executor.code(Bytes.fromHexString(bytecode));
        executor.sender(senderAddress);
        executor.receiver(contractAccount.getAddress());
        executor.worldUpdater(simpleWorld.updater());
        executor.commitWorldState();

        executor.callData(Bytes.fromHexString(allowance + senderAddress + owner + spender));
        executor.execute();
        int allowance = extractIntegerFromReturnData(byteArrayOutputStream);
        System.out.println(
            "Output of 'allowance(" + senderAddress + ", " + owner + ", " + spender + ")': " + Integer.toString(allowance)
        );
        return allowance;
    }

    public boolean transfer(String sender, String addressTo, int value) {
        Address senderAddress = getAddressFromString(sender);
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        PrintStream printStream = new PrintStream(byteArrayOutputStream);
        StandardJsonTracer tracer = new StandardJsonTracer(
            printStream,
            true,
            true,
            true,
            true
        );

        var executor = EVMExecutor.evm(EvmSpecVersion.CANCUN);
        executor.tracer(tracer);
        executor.code(Bytes.fromHexString(bytecode));
        executor.sender(senderAddress);
        executor.receiver(contractAccount.getAddress());
        executor.worldUpdater(simpleWorld.updater());
        executor.commitWorldState();

        executor.callData(Bytes.fromHexString(transfer + addressTo + value));
        executor.execute();
        boolean transferBool = extractBooleanFromReturnData(byteArrayOutputStream);
        System.out.println(
            "Output of 'transfer(" + senderAddress + ", " + addressTo + ", " + value + ")': " + Boolean.toString(transferBool)
        );
        return transferBool;
    }

    public boolean transferFrom(String sender, String from, String to, int value) {
        Address senderAddress = getAddressFromString(sender);
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        PrintStream printStream = new PrintStream(byteArrayOutputStream);
        StandardJsonTracer tracer = new StandardJsonTracer(
            printStream,
            true,
            true,
            true,
            true
        );

        var executor = EVMExecutor.evm(EvmSpecVersion.CANCUN);
        executor.tracer(tracer);
        executor.code(Bytes.fromHexString(bytecode));
        executor.sender(senderAddress);
        executor.receiver(contractAccount.getAddress());
        executor.worldUpdater(simpleWorld.updater());
        executor.commitWorldState();

        executor.callData(Bytes.fromHexString(transferFrom + from + to + value));
        executor.execute();
        boolean transferBool = extractBooleanFromReturnData(byteArrayOutputStream);
        System.out.println(
            "Output of 'transferFrom(" + senderAddress + ", " + from + ", " + to + ", " + value + ")': " + Boolean.toString(transferBool)
        );
        return transferBool;
    }

    public boolean increaseAllowance(String sender, String spender, int addedValue) {
        Address senderAddress = getAddressFromString(sender);
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        PrintStream printStream = new PrintStream(byteArrayOutputStream);
        StandardJsonTracer tracer = new StandardJsonTracer(
            printStream,
            true,
            true,
            true,
            true
        );

        var executor = EVMExecutor.evm(EvmSpecVersion.CANCUN);
        executor.tracer(tracer);
        executor.code(Bytes.fromHexString(bytecode));
        executor.sender(senderAddress);
        executor.receiver(contractAccount.getAddress());
        executor.worldUpdater(simpleWorld.updater());
        executor.commitWorldState();

        executor.callData(Bytes.fromHexString(increaseAllowance + spender + addedValue));
        executor.execute();
        boolean increaseBool = extractBooleanFromReturnData(byteArrayOutputStream);
        System.out.println(
            "Output of 'increaseAllowance(" + senderAddress + ", " + spender + ", " + addedValue + ")': " + Boolean.toString(increaseBool)
        );
        return increaseBool;
    }

    public boolean decreaseAllowance(String sender, String spender, int subtractedValue) {
        Address senderAddress = getAddressFromString(sender);
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        PrintStream printStream = new PrintStream(byteArrayOutputStream);
        StandardJsonTracer tracer = new StandardJsonTracer(
            printStream,
            true,
            true,
            true,
            true
        );

        var executor = EVMExecutor.evm(EvmSpecVersion.CANCUN);
        executor.tracer(tracer);
        executor.code(Bytes.fromHexString(bytecode));
        executor.sender(senderAddress);
        executor.receiver(contractAccount.getAddress());
        executor.worldUpdater(simpleWorld.updater());
        executor.commitWorldState();

        executor.callData(Bytes.fromHexString(decreaseAllowance + spender + subtractedValue));
        executor.execute();
        boolean decreaseBool = extractBooleanFromReturnData(byteArrayOutputStream);
        System.out.println(
            "Output of 'decreaseAllowance(" + senderAddress + ", " + spender + ", " + subtractedValue + ")': " + Boolean.toString(decreaseBool)
        );
        return decreaseBool;
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

    public static String convertIntegerToHex256Bit(int number) {
        BigInteger bigInt = BigInteger.valueOf(number);

        return String.format("%064x", bigInt);
    }

    public static String padHexStringTo256Bit(String hexString) {
        if (hexString.startsWith("0x")) {
            hexString = hexString.substring(2);
        }

        int length = hexString.length();
        int targetLength = 64;

        if (length >= targetLength) {
            return hexString.substring(0, targetLength);
        }

        return "0".repeat(targetLength - length) + hexString;
    }

    public static boolean extractBooleanFromReturnData(ByteArrayOutputStream byteArrayOutputStream) {
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

        java.math.BigInteger value = new java.math.BigInteger(returnData, 16);
        return value.equals(java.math.BigInteger.ONE);
    }

    public Address getAddressFromString(String address) {
            String fullHash = Hash.sha3String(address);

            // 2. An Ethereum Address is the LAST 20 bytes (40 characters) of the hash.
            // The fullHash is "0x" (2 chars) + 64 chars.
            // We need the last 40 characters.
            String addressPart = fullHash.substring(fullHash.length() - 40);

            Address accountAddress = Address.fromHexString("0x" + addressPart);
            return accountAddress;
    }

    public void seedBalance(String name, long amount) {
        // 1. Get the actual Ethereum Address for the name (e.g., "client1")
        Address user = getAddressFromString(name);
        
        // 2. Calculate the Storage Slot for: balances[user]
        // In your Solidity contract, 'balances' is the first mapping (Slot 0)
        // Formula: keccak256(padded_key + padded_mapping_slot)
        String paddedAddress = padHexStringTo256Bit(user.toHexString());
        String paddedSlot = convertIntegerToHex256Bit(0); // 'balances' is slot 0
        
        byte[] hashInput = Numeric.hexStringToByteArray(paddedAddress + paddedSlot);
        String storageKey = Numeric.toHexStringNoPrefix(Hash.sha3(hashInput));

        // 3. Write the balance directly into the Contract's storage
        contractAccount.setStorageValue(
            UInt256.fromHexString(storageKey), 
            UInt256.valueOf(amount)
        );
        
        System.out.println("Seeded " + name + " (" + user + ") at Slot " + storageKey + " with " + amount);
    }

    public static String convertStringToHex256Bit(String input) {
        String cleanHex;
        
        // Check if the input is already a Hex string (like an address)
        if (input.startsWith("0x")) {
            cleanHex = input.substring(2);
        } else if (input.matches("-?[0-9a-fA-F]+")) {
            // It's hex without the 0x prefix
            cleanHex = input;
        } else {
            // It's a plain string (like "client1"), convert text to hex bytes first
            cleanHex = org.apache.tuweni.bytes.Bytes.wrap(input.getBytes()).toUnprefixedHexString();
        }

        // Pad to 64 characters (256 bits)
        if (cleanHex.length() > 64) {
            // If it's a hash that's already 64 chars, return it; otherwise, truncate
            return cleanHex.substring(cleanHex.length() - 64);
        }
        
        return "0".repeat(64 - cleanHex.length()) + cleanHex;
    }
}