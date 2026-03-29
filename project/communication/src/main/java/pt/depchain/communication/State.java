package pt.depchain.communication;

public class State {
    private float balance;
    private int nonce;

    public State(float balance, int nonce) {
        this.balance = balance;
        this.nonce = nonce;
    }

    public float getBalance() {
        return balance;
    }
    public void setBalance(float balance) {
        this.balance = balance;
    }
    public int getNonce() {
        return nonce;
    }
    public void setNonce(int nonce) {
        this.nonce = nonce;
    }
}
