import { RENEW_MARGIN_MS } from "./config";
import { describeError, OAuthError, SignedOutError, UnreachableError } from "./errors";
import type { MeLoader, SignedInUser } from "./me";
import type { TokenClient, Tokens } from "./oauth";
import type { SignInPrompt } from "./sign-in-prompt";
import type { RefreshTokenStore } from "./token-store";

export type SessionState =
  /** Start-up: reading secure storage and renewing. */
  | { status: "loading" }
  /** Welcome screen with "Sign in". `notice` is set only when a sign-in attempt failed (never on cancel). */
  | { status: "signed-out"; notice?: "sign-in-failed" }
  /** Exchanging the authorization code for tokens (loading indicator). */
  | { status: "signing-in" }
  | { status: "signed-in"; user: SignedInUser }
  /** "Can't reach Washbase right now. Try again." The refresh token is kept. */
  | { status: "unreachable" };

export interface SessionDeps {
  store: RefreshTokenStore;
  tokens: TokenClient;
  loadMe: MeLoader;
  prompt: SignInPrompt;
  now?: () => number;
}

type Listener = () => void;

/**
 * The mobile app's session (ADR-001 Amendment 2):
 * - only the refresh token is persisted (secure storage); the access token lives in memory;
 * - a new refresh token is written to storage before its access token is used;
 * - renewal is single-flight: concurrent callers share one token request;
 * - renewal happens at start-up, when the app returns to the foreground, and when the access token is used within
 *   `RENEW_MARGIN_MS` of its expiry;
 * - an OAuth 4xx (e.g. `invalid_grant`) ends the session; a network error, timeout or 5xx keeps the refresh token
 *   and shows "unreachable".
 *
 * Never logs a token: only error class names and OAuth error codes.
 */
export class AuthSession {
  private state: SessionState = { status: "loading" };
  private readonly listeners = new Set<Listener>();
  private access: { token: string; expiresAt: number } | undefined;
  private renewal: Promise<void> | undefined;
  private readonly now: () => number;

  constructor(private readonly deps: SessionDeps) {
    this.now = deps.now ?? Date.now;
  }

  getState = (): SessionState => this.state;

  subscribe = (listener: Listener): (() => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };

  private setState(state: SessionState) {
    this.state = state;
    for (const listener of this.listeners) listener();
  }

  /** App start (and "Try again"): stored refresh token → renew → load the user; none → welcome. */
  async start(): Promise<void> {
    this.setState({ status: "loading" });
    let stored: string | null;
    try {
      stored = await this.deps.store.read();
    } catch (error) {
      console.warn(`Reading secure storage failed (${describeError(error)})`);
      stored = null;
    }
    if (!stored) {
      this.setState({ status: "signed-out" });
      return;
    }
    try {
      await this.renew();
    } catch {
      return; // renew() already moved to signed-out or unreachable.
    }
    await this.loadUser();
  }

  /** "Try again" on the unreachable screen. */
  retry(): Promise<void> {
    return this.start();
  }

  /** The app returned to the foreground: renew (which also extends the 30-day window) and reload if needed. */
  async onForeground(): Promise<void> {
    const before = this.state.status;
    if (before !== "signed-in" && before !== "unreachable") return;
    try {
      await this.renew();
    } catch {
      return;
    }
    if (before === "unreachable") await this.loadUser();
  }

  /**
   * The token source for `createApiClient(baseUrl, { getAccessToken })`. Returns the in-memory access token, renewing
   * it first when it expires within `RENEW_MARGIN_MS`. Never throws: `undefined` when there's no session.
   */
  getAccessToken = async (): Promise<string | undefined> => {
    if (this.access && this.access.expiresAt - this.now() > RENEW_MARGIN_MS) return this.access.token;
    try {
      await this.renew();
    } catch {
      return undefined;
    }
    return this.access?.token;
  };

  /** Single-flight renewal: every caller during a renewal shares the same token request. */
  renew(): Promise<void> {
    if (!this.renewal) {
      this.renewal = this.doRenew().finally(() => {
        this.renewal = undefined;
      });
    }
    return this.renewal;
  }

  private async doRenew(): Promise<void> {
    try {
      const stored = await this.deps.store.read();
      if (!stored) throw new SignedOutError("no stored refresh token");
      const tokens = await this.deps.tokens.refresh(stored);
      await this.persistThenUse(tokens);
    } catch (error) {
      if (error instanceof UnreachableError) {
        console.warn(`Renewing the session failed (${describeError(error)}); keeping it`);
        this.setState({ status: "unreachable" });
        throw error;
      }
      // OAuth 4xx (invalid_grant: expired after 30 days without use, revoked, replaced), no stored token, or a
      // storage failure: the session is over.
      console.warn(`Renewing the session failed (${describeError(error)}); signing out`);
      await this.clearLocal();
      this.setState({ status: "signed-out" });
      throw error instanceof SignedOutError ? error : new SignedOutError("renewal refused");
    }
  }

  /** Writes the new (rotated) refresh token to secure storage, and only then makes its access token usable. */
  private async persistThenUse(tokens: Tokens): Promise<void> {
    this.access = undefined;
    try {
      await this.deps.store.write(tokens.refreshToken);
    } catch (error) {
      console.warn(`Saving to secure storage failed (${describeError(error)})`);
      throw new SignedOutError("storage write failed");
    }
    this.access = { token: tokens.accessToken, expiresAt: tokens.expiresAt };
  }

  private async loadUser(): Promise<void> {
    const result = await this.deps.loadMe(this.getAccessToken);
    if (result.kind === "ok") {
      this.setState({ status: "signed-in", user: result.user });
      return;
    }
    if (result.kind === "unreachable") {
      // A renewal that was refused inside getAccessToken has already signed out: don't override it.
      if (this.state.status !== "signed-out") this.setState({ status: "unreachable" });
      return;
    }
    // 401/403 right after a fresh renewal or sign-in: the token isn't accepted (e.g. the account was deactivated).
    await this.clearLocal();
    this.setState({ status: "signed-out" });
  }

  /** "Sign in": opens the sign-in page, then exchanges the code. A closed page returns to welcome with no error. */
  async signIn(): Promise<void> {
    let outcome;
    try {
      outcome = await this.deps.prompt();
    } catch (error) {
      console.warn(`Opening the sign-in page failed (${describeError(error)})`);
      this.setState({ status: "signed-out", notice: "sign-in-failed" });
      return;
    }
    if (outcome.kind === "cancelled") {
      this.setState({ status: "signed-out" });
      return;
    }
    if (outcome.kind === "failed") {
      this.setState({ status: "signed-out", notice: "sign-in-failed" });
      return;
    }

    this.setState({ status: "signing-in" });
    try {
      const tokens = await this.deps.tokens.exchangeCode(outcome.code, outcome.codeVerifier, outcome.redirectUri);
      await this.persistThenUse(tokens);
    } catch (error) {
      console.warn(`Completing sign-in failed (${describeError(error)})`);
      if (error instanceof UnreachableError) {
        this.setState({ status: "unreachable" });
      } else {
        await this.clearLocal();
        this.setState({ status: "signed-out", notice: error instanceof OAuthError ? "sign-in-failed" : undefined });
      }
      return;
    }
    await this.loadUser();
  }

  /**
   * "Sign out": clears secure storage and memory first (so a killed app can't come back signed in), shows the
   * welcome screen, then revokes the refresh token (`token_type_hint=refresh_token`, `client_id` only), which also
   * ends its access token. If revoking can't reach the API, the phone is still signed out (ADR-001 accepted risk).
   */
  async signOut(): Promise<void> {
    await this.renewal?.catch(() => undefined);
    let stored: string | null = null;
    try {
      stored = await this.deps.store.read();
    } catch (error) {
      console.warn(`Reading secure storage failed (${describeError(error)})`);
    }
    await this.clearLocal();
    this.setState({ status: "signed-out" });
    if (!stored) return;
    try {
      await this.deps.tokens.revoke(stored);
    } catch (error) {
      console.warn(`Revoking the refresh token failed (${describeError(error)}); signed out on this phone only`);
    }
  }

  private async clearLocal(): Promise<void> {
    this.access = undefined;
    try {
      await this.deps.store.clear();
    } catch (error) {
      console.warn(`Clearing secure storage failed (${describeError(error)})`);
    }
  }
}
