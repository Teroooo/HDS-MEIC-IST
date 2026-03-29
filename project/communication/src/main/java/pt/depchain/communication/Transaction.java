package pt.depchain.communication;

public class Transaction {
    private String from;
    private String to;
    private String Input;
    private Float gas_price;
    private Float gas_limit;

    public Transaction(String from, String to, String input, Float gas_price, Float gas_limit) {
        this.from = from;
        this.to = to;
        this.Input = input;
        this.gas_price = gas_price;
        this.gas_limit = gas_limit;
    }

    public String getFrom() {
        return from;
    }

    public void setFrom(String from) {
        this.from = from;
    }

    public String getTo() {
        return to;
    }

    public void setTo(String to) {
        this.to = to;
    }

    public String getInput() {
        return Input;
    }

    public void setInput(String input) {
        this.Input = input;
    }

}
