import * as SecureStore from "expo-secure-store";

/**
 * Where the refresh token lives between app launches. Only the refresh token is ever persisted, and only in
 * `expo-secure-store` (Keychain / Android Keystore), never AsyncStorage. The access token stays in memory.
 */
export interface RefreshTokenStore {
  read(): Promise<string | null>;
  write(refreshToken: string): Promise<void>;
  clear(): Promise<void>;
}

export const REFRESH_TOKEN_KEY = "washbase.refreshToken";

/** `AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY`: readable after the first unlock (for renewal), never restored from a
 *  backup onto another device. */
export const SECURE_STORE_OPTIONS: SecureStore.SecureStoreOptions = {
  keychainAccessible: SecureStore.AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY,
};

export const secureRefreshTokenStore: RefreshTokenStore = {
  read: () => SecureStore.getItemAsync(REFRESH_TOKEN_KEY, SECURE_STORE_OPTIONS),
  write: (refreshToken) => SecureStore.setItemAsync(REFRESH_TOKEN_KEY, refreshToken, SECURE_STORE_OPTIONS),
  clear: () => SecureStore.deleteItemAsync(REFRESH_TOKEN_KEY, SECURE_STORE_OPTIONS),
};
