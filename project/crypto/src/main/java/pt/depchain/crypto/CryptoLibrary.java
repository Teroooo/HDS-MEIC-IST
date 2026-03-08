package pt.depchain.crypto;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.HashMap;
import java.util.Map;
import java.lang.reflect.Field;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import threshsig.KeyShare;
import threshsig.GroupKey;
import threshsig.SigShare;

import java.util.Base64;

public class CryptoLibrary {

    private final PrivateKey privateKey;

    private final PublicKey publicKey;

    private final HashMap<String, PublicKey> publicKeys = new HashMap<>();

    //-------------------------------------------------------------------------------------------

    public int k = 3;
    public int l = 4;

    private final GroupKey groupKey = null; // Placeholder values
    private final KeyShare[] keyShares = new KeyShare[l]; // Placeholder values
    private KeyShare myKey; // Placeholder values

    private BigInteger n = null; // Placeholder values
    private BigInteger e = null; // Placeholder values


    public CryptoLibrary(String privateKeyPath, String publicKeyPath, int myId) throws Exception {
        this.privateKey = readPrivateKey(privateKeyPath);
        this.publicKey = readPublicKey(publicKeyPath);
        loadGroupKey();
        loadKeyShares();
        loadMyKeyShare(myId);
        // Debug: print group parameters and my KeyShare
        System.out.println("[CryptoLibrary] Node " + myId + " groupKey n=" + n);
        System.out.println("[CryptoLibrary] Node " + myId + " groupKey e=" + e);
        System.out.println("[CryptoLibrary] Node " + myId + " k=" + k + ", l=" + l);
        if (myKey != null) {
            System.out.println("[CryptoLibrary] Node " + myId + " KeyShare id=" + myKey.getId());
        }
    }

    private void loadGroupKey() throws IOException {
        Gson gson = new Gson();
        String groupJsonStr = Files.readString(Paths.get("../config/groupKey.json"));
        Map<String, String> groupMap = gson.fromJson(
                groupJsonStr,
                new TypeToken<Map<String, String>>() {}.getType()
        );

        this.n = new BigInteger(groupMap.get("n"));
        this.e = new BigInteger(groupMap.get("e"));
    }

    private void loadKeyShares() throws Exception {
        Gson gson = new Gson();
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

            // Store the key share as needed
            signValField.set(ks, signVal);
            keyShares[i] = ks;
        }
    }

    private void loadMyKeyShare(int myId) throws Exception {
        if (myId > 0 && myId <= l) {
            this.myKey = keyShares[myId - 1];
        } else {
            throw new IllegalArgumentException("Invalid myId: " + myId);
        }
    }

    private static BigInteger factorial(int l) {
        BigInteger x = BigInteger.ONE;
        for (int i = 1; i <= l; i++) {
            x = x.multiply(BigInteger.valueOf(i));
        }
        return x;
    }

    public void addPublicKey(String nodeID, String publicKeyPath) throws Exception {
        PublicKey key = readPublicKey(publicKeyPath);
        publicKeys.put(nodeID, key);
    }

    public PublicKey getPublicKey(String nodeID) {
        return publicKeys.get(nodeID);
    }

    public PublicKey getMyPublicKey() {
        return publicKey;
    }

    public byte[] sign(byte[] data) throws Exception {
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(privateKey);
        sig.update(data);
        return sig.sign();
    }

    public boolean verify(byte[] data, byte[] signature, String nodeID) throws Exception {
        PublicKey key = publicKeys.get(nodeID);
        if (key == null) {
            throw new IllegalArgumentException("Unknown nodeID: " + nodeID);
        }
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initVerify(key);
        sig.update(data);
        return sig.verify(signature);
    }

    private static PrivateKey readPrivateKey(String filename) throws Exception {
        String keyPEM = new String(Files.readAllBytes(Paths.get(filename)))
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] keyBytes = Base64.getDecoder().decode(keyPEM);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return kf.generatePrivate(spec);
    }

    private static PublicKey readPublicKey(String filename) throws Exception {
        String keyPEM = new String(Files.readAllBytes(Paths.get(filename)))
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] keyBytes = Base64.getDecoder().decode(keyPEM);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return kf.generatePublic(spec);
    }

    public SigShare signShare(byte[] data) throws Exception {
        // Debug: print hash of data being signed
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(data);
        System.out.println("data: " + new String(data));
        System.out.println("[CryptoLibrary] Signing data hash: " + bytesToHex(hash));
        return myKey.sign(data);

    }


    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
    
    public boolean verifyShare(byte[] data, SigShare[] sigShares) throws Exception {
        return SigShare.verify(data, sigShares, 3, 4, n, e);
    }


}