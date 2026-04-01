package pt.depchain.communication;

public class Transaction {
    private String Type;
    private String from;
    private String to; // contract account
    private byte[] data; // encoded function call and arguments
    private long gasPrice;
    private long gasLimit;
    private int nonce; // used to order transactions from the same sender and prevent replay attacks
    private byte[] signature; 

    public Transaction(String Type, String from, String to, byte[] data, long gasPrice, long gasLimit, int nonce, byte[] signature) {
        this.Type = Type;
        this.from = from;
        this.to = to;
        this.data = data;
        this.gasPrice = gasPrice;
        this.gasLimit = gasLimit;
        this.nonce = nonce;
        this.signature = signature;
    }

    public String getType() {
        return Type;
    }

    public String getFrom() {
        return from;
    }

    public String getTo() {
        return to;
    }

    public byte[] getData() {
        return data;
    }

    public long getGasPrice() {
        return gasPrice;
    }

    public long getGasLimit() {
        return gasLimit;
    }

    public int getNonce() {
        return nonce;
    }

    public byte[] getSignature() {
        return signature;
    }

}
