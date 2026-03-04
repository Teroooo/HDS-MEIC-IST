package pt.depchain.crypto;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.HashMap;
import java.util.Base64;

public class CryptoLibrary {

    private final PrivateKey privateKey;

    private final PublicKey publicKey;

    private final HashMap<String, PublicKey> publicKeys = new HashMap<>();

    public CryptoLibrary(String privateKeyPath, String publicKeyPath) throws Exception {
        this.privateKey = readPrivateKey(privateKeyPath);
        this.publicKey = readPublicKey(publicKeyPath);
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
}