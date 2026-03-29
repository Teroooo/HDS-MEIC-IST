package pt.depchain.communication;

import com.google.gson.Gson;
import java.io.FileReader;
import java.io.IOException;

public class TestBlocks {
    public static void main(String[] args) {
        Gson gson = new Gson();

        try (FileReader reader = new FileReader("../config/genesis.json")) {
            // Converts the JSON text into a Block object
            Block genesisBlock = gson.fromJson(reader, Block.class);

            // Accessing the data
            System.out.println("Loaded Block Hash: " + genesisBlock.getHash());
            System.out.println("Transactions found: " + genesisBlock.getTransactions().size());
            System.out.println(genesisBlock.toString());
            
        } catch (IOException e) {
            System.err.println("Could not find or read the genesis file!");
            e.printStackTrace();
        }
    }
}