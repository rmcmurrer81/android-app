import com.kiraworld.sarahtravel.TrustedSyncProtocol;

/** Fixed Python-created vector proving Windows and Android use the same wire crypto. */
public final class SarahSecureSyncInteropTest {
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception{
        String token="AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        String encrypted="AAECAwQFBgcICQoL.T+mZFJo7OsZvA0zUn/WyOcjVNZe0pSWfmXRwCmcQDk6c6BQtO19DBZYqLl5Vef5PC8M3F5/iXmi+rcaDpYt6Ei/h4lrpBbP9VwF53++o0//lpigEEb4q2SkXmuqmv4eJqGVM0ddC";
        String expected="{\"kind\":\"preview_request\",\"device_id\":\"android-owner-device\",\"request_id\":\"interop-1\"}";
        String signature="vIL0hNhA0H+AkwJ6ua6kUqD8K7bhm3lZE/Eonpf0nWg=";
        require(expected.equals(TrustedSyncProtocol.decrypt(token,encrypted)),
                "Android must decrypt the exact Python/Windows AES-GCM vector");
        require(signature.equals(TrustedSyncProtocol.signature(token,encrypted)),
                "Android must reproduce the exact Python/Windows HMAC vector");
        System.out.println("Sarah secure-sync Python/Android interop vector passed");
    }
}
