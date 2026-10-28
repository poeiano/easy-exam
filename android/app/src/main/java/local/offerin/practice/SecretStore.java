package local.offerin.practice;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;

public final class SecretStore {
    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
        if (ks.containsAlias("offerin-api-key")) return (SecretKey) ks.getKey("offerin-api-key", null);
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder("offerin-api-key", KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return g.generateKey();
    }
    public static void save(Context ctx, String text) throws Exception {
        saveSlot(ctx,"key",text);
    }
    public static void saveSlot(Context ctx,String slot,String text)throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key());
        String value = Base64.encodeToString(c.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(c.doFinal(text.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        ctx.getSharedPreferences("private", 0).edit().putString(slot, value).apply();
    }
    public static String read(Context ctx) throws Exception {
        return readSlot(ctx,"key");
    }
    public static void clearConnection(Context ctx){ctx.getSharedPreferences("private",0).edit().remove("connection").apply();}
    public static String readSlot(Context ctx,String slot)throws Exception {
        String value = ctx.getSharedPreferences("private", 0).getString(slot, ""); if (value.isEmpty()) return "";
        String[] v = value.split(":", 2); Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(v[0], Base64.NO_WRAP)));
        return new String(c.doFinal(Base64.decode(v[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }
}
