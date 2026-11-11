package com.motonav.companion;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Only encrypted API-key bytes are persisted; the encryption key never leaves AndroidKeyStore. */
final class SavedMapConfig {
    private static final String ALIAS="motonav.recovery.map.v1";
    private final SharedPreferences prefs;
    SavedMapConfig(Context context){prefs=context.getSharedPreferences("map_config_v1",Context.MODE_PRIVATE);}
    private SecretKey key() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(store.containsAlias(ALIAS))return (SecretKey)store.getKey(ALIAS,null);
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
    void save(String value) throws Exception {
        if(!AmapAccess.configured(value))throw new IllegalArgumentException();
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());
        byte[] plain=value.getBytes(StandardCharsets.UTF_8);
        try{
            byte[] encrypted=cipher.doFinal(plain);
            if(!prefs.edit().putString("cipher",Base64.encodeToString(encrypted,Base64.NO_WRAP))
                .putString("iv",Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)).commit())throw new java.io.IOException();
        }finally{java.util.Arrays.fill(plain,(byte)0);}
    }
    String load() throws Exception {
        if(!prefs.contains("cipher"))return "";
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(prefs.getString("iv",""),Base64.NO_WRAP)));
        byte[] plain=cipher.doFinal(Base64.decode(prefs.getString("cipher",""),Base64.NO_WRAP));
        try{return new String(plain,StandardCharsets.UTF_8);}finally{java.util.Arrays.fill(plain,(byte)0);}
    }
    boolean consent(){return prefs.getInt("consent_version",0)==2;}
    boolean consent(boolean value){return prefs.edit().putInt("consent_version",value?2:0).commit();}
    boolean asked(){return prefs.getBoolean("permission_asked",false);}
    void asked(boolean value){prefs.edit().putBoolean("permission_asked",value).apply();}
    boolean clear(){
        boolean keyRemoved=true;
        try{KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);store.deleteEntry(ALIAS);}
        catch(Exception e){keyRemoved=false;}
        boolean dataRemoved=prefs.edit().remove("cipher").remove("iv").remove("consent_version").commit();
        return keyRemoved&&dataRemoved;
    }
}
