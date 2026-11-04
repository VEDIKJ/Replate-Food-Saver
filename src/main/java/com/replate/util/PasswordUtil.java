package com.replate.util;

import org.mindrot.jbcrypt.BCrypt;

/*
 * PasswordUtil hashes and checks passwords using BCrypt (the jBCrypt library).
 *
 * WHY BCRYPT?  We never store the real password. Instead we store a one-way
 * "hash". BCrypt also:
 *   * adds a random "salt" to every hash, so two users with the same password
 *     get different stored values, and
 *   * is deliberately slow, which makes guessing attacks far harder.
 *
 * A stored hash looks like:  $2a$12$R9h/....  (always 60 characters).
 */
public final class PasswordUtil {

    // "Cost" factor: higher = slower = harder to brute force. 12 is a good default.
    private static final int WORK_FACTOR = 12;

    private PasswordUtil() {
    }

    // Turn a plain password into a salted BCrypt hash for storing in the DB.
    public static String hash(String plainPassword) {
        return BCrypt.hashpw(plainPassword, BCrypt.gensalt(WORK_FACTOR));
    }

    // Check a plain password typed at login against the stored hash.
    // Returns true only if they match.
    public static boolean matches(String plainPassword, String storedHash) {
        if (plainPassword == null || storedHash == null || storedHash.isEmpty()) {
            return false;
        }
        try {
            return BCrypt.checkpw(plainPassword, storedHash);
        } catch (IllegalArgumentException e) {
            // Thrown if the stored value is not a valid BCrypt hash.
            return false;
        }
    }
}
