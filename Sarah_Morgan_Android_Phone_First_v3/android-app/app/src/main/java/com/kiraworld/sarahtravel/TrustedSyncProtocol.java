package com.kiraworld.sarahtravel;
import java.nio.charset.StandardCharsets; import java.security.SecureRandom; import java.util.Base64; import javax.crypto.Cipher; import javax.crypto.Mac; import javax.crypto.SecretKeyFactory; import javax.crypto.spec.GCMParameterSpec; import javax.crypto.spec.PBEKeySpec; import javax.crypto.spec.SecretKeySpec;
public final class TrustedSyncProtocol {
    private TrustedSyncProtocol(){}
    public static String encrypt(String token,String text)throws Exception{byte[] iv=new byte[12];new SecureRandom().nextBytes(iv);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key(token),new GCMParameterSpec(128,iv));byte[] out=c.doFinal(text.getBytes(StandardCharsets.UTF_8));return Base64.getEncoder().encodeToString(iv)+"."+Base64.getEncoder().encodeToString(out);}
    public static String decrypt(String token,String value)throws Exception{String[] p=value.split("\\.",2);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(token),new GCMParameterSpec(128,Base64.getDecoder().decode(p[0])));return new String(c.doFinal(Base64.getDecoder().decode(p[1])),StandardCharsets.UTF_8);}
    public static String signature(String token,String encrypted)throws Exception{Mac m=Mac.getInstance("HmacSHA256");m.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return Base64.getEncoder().encodeToString(m.doFinal(encrypted.getBytes(StandardCharsets.UTF_8)));}
    private static SecretKeySpec key(String token)throws Exception{PBEKeySpec spec=new PBEKeySpec(token.toCharArray(),"SarahTrustedSyncV1".getBytes(StandardCharsets.UTF_8),120000,256);byte[] k=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();return new SecretKeySpec(k,"AES");}
}
