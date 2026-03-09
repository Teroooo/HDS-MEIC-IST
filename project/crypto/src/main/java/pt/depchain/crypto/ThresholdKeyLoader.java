package pt.depchain.crypto;

import threshsig.*;
import java.lang.reflect.Field;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Random;
import java.util.Map;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

public class ThresholdKeyLoader {

    public static void main(String[] args) throws Exception {

        int k = 3;
        int l = 4;

        Gson gson = new Gson();

        // ---------------- LOAD GROUP KEY ----------------
        String groupJsonStr = Files.readString(Paths.get("../config/groupKey.json"));
        Map<String, String> groupMap = gson.fromJson(
                groupJsonStr,
                new TypeToken<Map<String, String>>() {}.getType()
        );

        BigInteger n = new BigInteger(groupMap.get("n"));
        BigInteger e = new BigInteger(groupMap.get("e"));

        System.out.println("Group modulus n = " + n);
        System.out.println("Group exponent e = " + e);

        // ---------------- LOAD SHARES ----------------
        KeyShare[] shares = new KeyShare[l];

        Field signValField = KeyShare.class.getDeclaredField("signVal");
        signValField.setAccessible(true);

        for (int i = 0; i < l; i++) {

            String shareJsonStr = Files.readString(Paths.get("../config/node" + (i + 1) + ".share.json"));

            Map<String, String> sMap = gson.fromJson(
                    shareJsonStr,
                    new TypeToken<Map<String, String>>() {}.getType()
            );

            int id = Integer.parseInt(sMap.get("id"));
            BigInteger secret = new BigInteger(sMap.get("secret"));
            BigInteger verifier = new BigInteger(sMap.get("verifier"));
            BigInteger groupVerifier = new BigInteger(sMap.get("groupVerifier"));
            BigInteger shareN = new BigInteger(sMap.get("n"));
            BigInteger signVal = new BigInteger(sMap.get("signVal"));

            KeyShare ks = new KeyShare(id, secret, shareN, factorial(l));
            ks.setVerifiers(verifier, groupVerifier);

            signValField.set(ks, signVal);

            shares[i] = ks;
        }

        System.out.println("Loaded " + shares.length + " shares\n");

        byte[] message = "Test threshold signature".getBytes();

        // ---------------- TEST 1: VALID THRESHOLD ----------------
        System.out.println("TEST 1: Valid threshold signature");

        KeyShare[] signingShares = pickRandomShares(shares, k);
        SigShare[] sigShares = signShares(signingShares, message);


        boolean verified = SigShare.verify(message, sigShares, k, l, n, e);
        System.err.println("message: " + message);
        for(SigShare s : sigShares) {
            System.out.println("Share from node " + s);
        }
        System.out.println("k: " + k); 
        System.out.println("l: " + l);
        System.out.println("n: " + n);
        System.out.println("e: " + e);

        System.out.println("Expected: true");
        System.out.println("Result: " + verified + "\n");


        // ---------------- TEST 2: INSUFFICIENT SHARES ----------------
        System.out.println("TEST 2: Insufficient shares (k-1)");

        KeyShare[] insufficientShares = pickRandomShares(shares, k - 1);
        SigShare[] badSigShares = new SigShare[k];

        for (int i = 0; i < k - 1; i++) {
            badSigShares[i] = shares[i].sign(message);
        }

        
        try {
            boolean badVerify = SigShare.verify(message, badSigShares, k, l, n, e);
            System.out.println("Verification with insufficient shares: " + badVerify);
        } catch (Exception ex) {
            System.out.println("Verification failed as expected: " + ex.getMessage());
        }

        System.out.println("Expected: false");


        // ---------------- TEST 3: WRONG MESSAGE ----------------
        System.out.println("TEST 3: Wrong message verification");

        byte[] fakeMessage = "Fake message".getBytes();

        boolean fakeVerify = SigShare.verify(fakeMessage, sigShares, k, l, n, e);

        System.out.println("Expected: false");
        System.out.println("Result: " + fakeVerify + "\n");


        // ---------------- TEST 4: TAMPERED SHARE ----------------
        System.out.println("TEST 4: Tampered signature share");

        SigShare[] tamperedShares = sigShares.clone();

        tamperedShares[0] = new SigShare(
                tamperedShares[0].getId(),
                tamperedShares[0].getSig().add(BigInteger.ONE),
                tamperedShares[0].getSigVerifier()
        );

        boolean tamperedVerify = SigShare.verify(message, tamperedShares, k, l, n, e);

        System.out.println("Expected: false");
        System.out.println("Result: " + tamperedVerify + "\n");

        System.out.println("All tests completed.");
    }


    // ---------------- UTILITIES ----------------

    private static SigShare[] signShares(KeyShare[] shares, byte[] message) {
        SigShare[] sigShares = new SigShare[shares.length];

        for (int i = 0; i < shares.length; i++) {
            sigShares[i] = shares[i].sign(message);
        }

        return sigShares;
    }

    private static KeyShare[] pickRandomShares(KeyShare[] shares, int count) {

        Random rnd = new Random();
        KeyShare[] result = new KeyShare[count];

        for (int i = 0; i < count; i++) {
            int idx;
            do {
                idx = rnd.nextInt(shares.length);
            } while (contains(result, shares[idx]));

            result[i] = shares[idx];
        }

        return result;
    }

    private static boolean contains(KeyShare[] array, KeyShare value) {
        for (KeyShare ks : array) {
            if (ks == value) return true;
        }
        return false;
    }

    private static BigInteger factorial(int l) {
        BigInteger x = BigInteger.ONE;
        for (int i = 1; i <= l; i++) {
            x = x.multiply(BigInteger.valueOf(i));
        }
        return x;
    }
}