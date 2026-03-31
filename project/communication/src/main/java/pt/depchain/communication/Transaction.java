package pt.depchain.communication;

public class Transaction {
    private String from;
    private String operation;
    private String[] args;
    private long gasPrice;
    private long gasLimit;
    private int nonce; // used to order transactions from the same sender and prevent replay attacks

    public Transaction(String from, String operation, String[] args, long gasPrice, long gasLimit, int nonce) {
        this.from = from;
        this.operation = operation;
        this.args = args;
        this.gasPrice = gasPrice;
        this.gasLimit = gasLimit;
        this.nonce = nonce;
    }

    public String getFrom() {
        return from;
    }

    public String getOperation() {
        return operation;
    }

    public String[] getArgs() {
        return args;
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

}
