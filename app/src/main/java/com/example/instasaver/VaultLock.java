package com.example.instasaver;

/**
 * Tracks whether the hidden vault is currently "unlocked".
 *
 * The vault re-locks automatically when:
 *   - the app goes to the background (all vault screens stop), or
 *   - it has been idle past a timeout.
 *
 * Re-entering then requires the secret 3-second press on the InstaSaver title.
 */
public final class VaultLock {

    /** Auto-lock after this much inactivity while the vault is open. */
    private static final long TIMEOUT_MS = 60_000;

    private static boolean unlocked = false;
    private static int visibleVaultScreens = 0;
    private static long lastActive = 0;

    private VaultLock() { }

    public static void unlock() {
        unlocked = true;
        lastActive = System.currentTimeMillis();
    }

    public static boolean isUnlocked() {
        return unlocked && (System.currentTimeMillis() - lastActive) < TIMEOUT_MS;
    }

    /** Refresh the idle timer (call on user interaction inside the vault). */
    public static void touch() {
        lastActive = System.currentTimeMillis();
    }

    public static void onVaultScreenStart() {
        visibleVaultScreens++;
    }

    /** When no vault screen is visible, the app has backgrounded → lock. */
    public static void onVaultScreenStop() {
        visibleVaultScreens--;
        if (visibleVaultScreens <= 0) {
            visibleVaultScreens = 0;
            unlocked = false;
        }
    }
}
