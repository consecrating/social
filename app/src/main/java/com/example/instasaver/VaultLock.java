package com.example.instasaver;

/**
 * Tracks whether the hidden vault is currently "unlocked".
 *
 * The vault re-locks automatically when the app truly goes to the background, or
 * after an idle timeout. It must NOT lock when the vault itself launches an
 * internal picker/dialog (gallery import, cover picker, delete confirmation) —
 * otherwise returning from that picker would eject the user from the vault.
 */
public final class VaultLock {

    /** Auto-lock after this much inactivity while the vault is open. */
    private static final long TIMEOUT_MS = 60_000;

    private static boolean unlocked = false;
    private static int visibleVaultScreens = 0;
    private static long lastActive = 0;
    /** True while the vault has intentionally launched one of its own pickers. */
    private static boolean internalActivity = false;

    private VaultLock() { }

    public static void unlock() {
        unlocked = true;
        lastActive = System.currentTimeMillis();
    }

    public static boolean isUnlocked() {
        return unlocked && (System.currentTimeMillis() - lastActive) < TIMEOUT_MS;
    }

    public static void touch() {
        lastActive = System.currentTimeMillis();
    }

    public static void onVaultScreenStart() {
        visibleVaultScreens++;
    }

    /** When no vault screen is visible AND we didn't launch our own picker → lock. */
    public static void onVaultScreenStop() {
        visibleVaultScreens--;
        if (visibleVaultScreens <= 0) {
            visibleVaultScreens = 0;
            if (!internalActivity) {
                unlocked = false;
            }
        }
    }

    /** Call right before the vault launches its own picker/confirm dialog. */
    public static void beginInternalActivity() {
        internalActivity = true;
        lastActive = System.currentTimeMillis();
    }

    /**
     * Call from a vault screen's onResume. If we were returning from our own
     * picker, keep the vault unlocked and report true so the caller skips the
     * lock check.
     */
    public static boolean consumeInternalActivity() {
        if (internalActivity) {
            internalActivity = false;
            unlocked = true;
            lastActive = System.currentTimeMillis();
            return true;
        }
        return false;
    }
}
