package pt.depchain.communication;

public class Transaction {
    private String from;
    private String to;
    private Float input;
    private Float gasPrice;
    private Float gasLimit;
    private Float transactionFee; // used to order transactions from different senders based on fee paid
    private int nounce; // used to order transactions from the same sender and prevent replay attacks
    private static final Float GAS_USED = 1f; // Fixed gas used for a simple transfer

    public Transaction(String from, String to, Float input, Float gas_price, Float gas_limit, int nounce) {
        if (gas_price <= 0 || gas_limit <= 0) {
            throw new IllegalArgumentException("Gas price and gas limit must be positive.");
        }
        this.from = from;
        this.to = to;
        this.nounce = nounce;
        this.input = input;
        this.gasPrice = gas_price;
        this.gasLimit = gas_limit;
        this.transactionFee = Math.min(gas_price * gas_limit, gas_price * GAS_USED);
    }

    public String getFrom() {
        return from;
    }

    public void setFrom(String from) {
        this.from = from;
    }

    public String getDest() {
        return to;
    }

    public void setDest(String to) {
        this.to = to;
    }

    public Float getInput() {
        return input;
    }

    public void setInput(Float input) {
        this.input = input;
    }

    public Float getGasPrice() {
        return gasPrice;
    }

    public void setGasPrice(Float gas_price) {
        this.gasPrice = gas_price;
    }

    public Float getGasLimit() {
        return gasLimit;
    }

    public void setGasLimit(Float gas_limit) {
        this.gasLimit = gas_limit;
    }

    public Float getTransactionFee() {
        return transactionFee;
    }

    public void setTransactionFee(Float transaction_fee) {
        this.transactionFee = transaction_fee;
    }

    public int getNounce() {
        return nounce;
    }

    public void setNounce(int nounce) {
        this.nounce = nounce;
    }

    public String getRequestKey() {
        return from + "-" + nounce;
    }
}
