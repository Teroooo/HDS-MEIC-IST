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

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

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

    public int k = 3;
    public int l = 4;

    private final GroupKey groupKey = null;
    private final KeyShare[] keyShares = new KeyShare[l];
    private KeyShare myKey;

    private BigInteger n = null;
    private BigInteger e = null;

    private final Map<String, SecretKey> symmetricKeys = new HashMap<>();

    public CryptoLibrary(String privateKeyPath, String publicKeyPath, String myId) throws Exception {
        this.privateKey = readPrivateKey(privateKeyPath);
        this.publicKey = readPublicKey(publicKeyPath);
        loadGroupKey();
        loadKeyShares();
        loadMyKeyShare(myId);
    }

    public CryptoLibrary(String privateKeyPath, String publicKeyPath) throws Exception {
        this.privateKey = readPrivateKey(privateKeyPath);
        this.publicKey = readPublicKey(publicKeyPath); 
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

    private void loadMyKeyShare(String myId) throws Exception {
        int id = Integer.parseInt(myId);
        if (id  > 0 && id  <= l) {
            this.myKey = keyShares[id  - 1];
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

    public SecretKey generateAESKey() throws Exception {
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(128); // or 256 if allowed
        return keyGen.generateKey();
    }

    public String encryptAESKey(SecretKey aesKey, PublicKey publicKey) throws Exception {
        Cipher cipher = Cipher.getInstance("RSA");
        cipher.init(Cipher.ENCRYPT_MODE, publicKey);

        byte[] encryptedBytes = cipher.doFinal(aesKey.getEncoded());

        return Base64.getEncoder().encodeToString(encryptedBytes);
    }

    public SecretKey decryptAESKey(String encryptedKey) throws Exception {
        byte[] encryptedBytes = Base64.getDecoder().decode(encryptedKey);

        Cipher cipher = Cipher.getInstance("RSA");
        cipher.init(Cipher.DECRYPT_MODE, privateKey);

        byte[] decodedKey = cipher.doFinal(encryptedBytes);

        return new SecretKeySpec(decodedKey, 0, decodedKey.length, "AES");
    }

    public void addSymmetricKey(String nodeId, SecretKey key) {
        symmetricKeys.put(nodeId, key);
    }

    public Map<String, SecretKey> getSymmetricKeys() {
        return symmetricKeys;
    }

    public SecretKey getSymmetricKey(String nodeId) {
        return symmetricKeys.get(nodeId);
    }

    public byte[] encryptAES(byte[] data, String nodeId) throws Exception {
        SecretKey key = symmetricKeys.get(nodeId);

        if (key == null) {
            System.out.println("No symmetric key for node: " + nodeId);
            
        }
        Cipher cipher = Cipher.getInstance("AES");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        return cipher.doFinal(data);
    }

    public byte[] decryptAES(byte[] encryptedData, String nodeId) throws Exception {
        SecretKey key = symmetricKeys.get(nodeId);

        if (key == null) {
            System.out.println("No symmetric key for node: " + nodeId);
        }
        Cipher cipher = Cipher.getInstance("AES");
        cipher.init(Cipher.DECRYPT_MODE, key);
        return cipher.doFinal(encryptedData);
    }
}