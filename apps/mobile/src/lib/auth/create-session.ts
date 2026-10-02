import { makeRedirectUri } from "expo-auth-session";

import { authConfig, REDIRECT_PATH, REDIRECT_SCHEME } from "./config";
import { createMeLoader } from "./me";
import { createEndpointResolver, createTokenClient } from "./oauth";
import { AuthSession } from "./session";
import { createSignInPrompt } from "./sign-in-prompt";
import { secureRefreshTokenStore } from "./token-store";

/**
 * The app's real session: secure storage, the authorization server from `EXPO_PUBLIC_AUTH_ISSUER`, and the API from
 * `EXPO_PUBLIC_API_BASE_URL`. In Expo Go the redirect URI is `exp://<LAN-IP>:8081/--/auth/callback`; in a development
 * or production build it is `washbase://auth/callback`. Both must be registered on the API (`pnpm dev:all --lan`).
 */
export function createAuthSession(): AuthSession {
  const { apiBaseUrl, issuer } = authConfig();
  const redirectUri = makeRedirectUri({ scheme: REDIRECT_SCHEME, path: REDIRECT_PATH });
  const resolveEndpoints = createEndpointResolver(issuer);
  return new AuthSession({
    store: secureRefreshTokenStore,
    tokens: createTokenClient(resolveEndpoints),
    loadMe: createMeLoader(apiBaseUrl),
    prompt: createSignInPrompt(redirectUri, resolveEndpoints),
  });
}
