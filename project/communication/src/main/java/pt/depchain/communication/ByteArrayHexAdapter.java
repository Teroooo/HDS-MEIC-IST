package pt.depchain.communication;

import com.google.gson.*;
import java.lang.reflect.Type;

public class ByteArrayHexAdapter implements JsonSerializer<byte[]>, JsonDeserializer<byte[]> {

    @Override
    public JsonElement serialize(byte[] src, Type typeOfSrc, JsonSerializationContext context) {
        if (src == null) return JsonNull.INSTANCE;
        return new JsonPrimitive(bytesToHex(src));
    }

    @Override
    public byte[] deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) {
        if (json == null) return null;
        return hexToBytes(json.getAsString());
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder("0x");
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private byte[] hexToBytes(String hex) {
        hex = hex.startsWith("0x") ? hex.substring(2) : hex;
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                                + Character.digit(hex.charAt(i+1), 16));
        }
        return data;
    }
}