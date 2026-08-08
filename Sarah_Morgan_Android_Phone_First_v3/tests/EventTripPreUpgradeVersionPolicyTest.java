package com.kiraworld.sarahtravel;

public final class EventTripPreUpgradeVersionPolicyTest {
    public static void main(String[] args) {
        require(EventTripPreUpgradeVersionPolicy.requiresBackup(1),
                "R1 must be backed up before v2 opens");
        require(!EventTripPreUpgradeVersionPolicy.mayOpenV2(1, false),
                "unverified R1 backup must block v2");
        require(EventTripPreUpgradeVersionPolicy.mayOpenV2(1, true),
                "hash-verified R1 backup permits the bounded v2 open");
        require(EventTripPreUpgradeVersionPolicy.mayOpenV2(2, false),
                "an existing exact v2 database does not need an R1 backup");
        require(EventTripPreUpgradeVersionPolicy.unexpected(0),
                "user_version 0 must fail closed");
        require(EventTripPreUpgradeVersionPolicy.unexpected(3),
                "future or unknown versions must fail closed");
        require(!EventTripPreUpgradeVersionPolicy.mayOpenV2(0, true),
                "a backup flag cannot authorize an unexpected version");
        require(!EventTripPreUpgradeVersionPolicy.mayOpenV2(3, true),
                "a backup flag cannot downgrade a future version");
        System.out.println("EventTripPreUpgradeVersionPolicyTest passed");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
