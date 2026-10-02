import * as client from "openid-client";
import { describe, expect, it, vi } from "vitest";
import { AuthUnavailableError, SignInFailedError, toAuthError } from "@/lib/auth/oidc";
import {
  REDIRECT_URI,
  fakeAuthorizationServer,
  networkError,
  serverMetadata,
  timeoutError,
  type TokenEndpoint,
} from "@/test/fake-authorization-server";

const CODE = "test-authorization-code-5b1f";
const STATE = "test-state-9d2c";
const CODE_VERIFIER = "test-code-verifier-0e7a0e7a0e7a0e7a0e7a0e7a0e7a0e7a0e7a0e7a";
const CLIENT_SECRET = "test-client-secret-77c4";
const ERROR_DESCRIPTION = `The code ${CODE} was already used`;
const ACCESS_TOKEN = "test-access-token-eyJ-31aa";

/** The error a real openid-client `authorizationCodeGrant` rejects with, for this callback query and token endpoint. */
async function codeGrantError(callbackQuery: string, tokenEndpoint: TokenEndpoint = () => Response.json({})) {
  const config = new client.Configuration(serverMetadata, "washbase-web", undefined, client.ClientSecretBasic(CLIENT_SECRET));
  client.allowInsecureRequests(config);
  vi.stubGlobal("fetch", fakeAuthorizationServer(tokenEndpoint));
  const currentUrl = new URL(`${REDIRECT_URI}?${callbackQuery}`);
  try {
    await client.authorizationCodeGrant(config, currentUrl, { pkceCodeVerifier: CODE_VERIFIER, expectedState: STATE });
  } catch (error) {
    return error;
  }
  throw new Error("authorizationCodeGrant was expected to fail");
}

/** A token endpoint that answers with this status and JSON body. */
function answering(status: number, body: unknown): TokenEndpoint {
  return () => Response.json(body, { status });
}

/** Nothing secret from the original error survives in ours: no message, cause, description, code or token. */
function expectNothingCarriedOver(mapped: Error) {
  expect(mapped.cause).toBeUndefined();
  for (const secret of [CODE, CODE_VERIFIER, CLIENT_SECRET, ERROR_DESCRIPTION, ACCESS_TOKEN]) {
    expect(mapped.message).not.toContain(secret);
    expect(JSON.stringify(mapped)).not.toContain(secret);
  }
}

describe("toAuthError: the authorization server can't be reached", () => {
  it("maps a network failure (fetch failed) to AuthUnavailableError", async () => {
    const mapped = toAuthError(
      await codeGrantError(`code=${CODE}&state=${STATE}`, () => {
        throw networkError();
      }),
    );

    expect(mapped).toBeInstanceOf(AuthUnavailableError);
    expect(mapped.message).toBe("The authorization server can't be reached");
    expectNothingCarriedOver(mapped);
  });

  it("maps a timeout to AuthUnavailableError", async () => {
    const mapped = toAuthError(
      await codeGrantError(`code=${CODE}&state=${STATE}`, () => {
        throw timeoutError();
      }),
    );

    expect(mapped).toBeInstanceOf(AuthUnavailableError);
    expectNothingCarriedOver(mapped);
  });

  it("maps an aborted request to AuthUnavailableError", () => {
    expect(toAuthError(new DOMException("This operation was aborted", "AbortError"))).toBeInstanceOf(AuthUnavailableError);
  });

  it("maps a 5xx OAuth error body from the token endpoint to AuthUnavailableError", async () => {
    const mapped = toAuthError(
      await codeGrantError(
        `code=${CODE}&state=${STATE}`,
        answering(503, { error: "temporarily_unavailable", error_description: ERROR_DESCRIPTION }),
      ),
    );

    expect(mapped).toBeInstanceOf(AuthUnavailableError);
    expectNothingCarriedOver(mapped);
  });

  it("maps a ResponseBodyError with a 5xx status to AuthUnavailableError", () => {
    const original = new client.ResponseBodyError("server responded with an error in the response body", {
      cause: { error: "server_error", error_description: ERROR_DESCRIPTION },
      response: new Response(null, { status: 500 }),
    });

    const mapped = toAuthError(original);

    expect(mapped).toBeInstanceOf(AuthUnavailableError);
    expectNothingCarriedOver(mapped);
  });

  it("maps a 5xx non-OAuth answer (e.g. a proxy's HTML error page) to AuthUnavailableError", async () => {
    const original = await codeGrantError(
      `code=${CODE}&state=${STATE}`,
      () => new Response("<html>Bad gateway</html>", { status: 502, headers: { "content-type": "text/html" } }),
    );
    expect(original).toBeInstanceOf(client.ClientError);

    const mapped = toAuthError(original);

    expect(mapped).toBeInstanceOf(AuthUnavailableError);
    expectNothingCarriedOver(mapped);
  });
});

describe("toAuthError: the sign-in failed", () => {
  it("maps an OAuth error from the token endpoint (4xx invalid_grant) to SignInFailedError without its description", async () => {
    const original = await codeGrantError(
      `code=${CODE}&state=${STATE}`,
      answering(400, { error: "invalid_grant", error_description: ERROR_DESCRIPTION }),
    );

    const mapped = toAuthError(original);

    expect(mapped).toBeInstanceOf(SignInFailedError);
    expect(mapped.message).toBe("Sign-in didn't complete (ResponseBodyError, OAUTH_RESPONSE_BODY_ERROR, invalid_grant)");
    expectNothingCarriedOver(mapped);
  });

  it("maps an error= authorization response (access_denied) to SignInFailedError without its description", async () => {
    const original = await codeGrantError(
      `error=access_denied&error_description=${encodeURIComponent(ERROR_DESCRIPTION)}&state=${STATE}`,
    );

    const mapped = toAuthError(original);

    expect(mapped).toBeInstanceOf(SignInFailedError);
    expect(mapped.message).toBe(
      "Sign-in didn't complete (AuthorizationResponseError, OAUTH_AUTHORIZATION_RESPONSE_ERROR, access_denied)",
    );
    expectNothingCarriedOver(mapped);
  });

  it("maps a state mismatch to SignInFailedError", async () => {
    const original = await codeGrantError(`code=${CODE}&state=someone-elses-state`);

    const mapped = toAuthError(original);

    expect(mapped).toBeInstanceOf(SignInFailedError);
    expect(mapped.message).toBe("Sign-in didn't complete (ClientError, OAUTH_INVALID_RESPONSE)");
    expectNothingCarriedOver(mapped);
  });

  it("maps an openid-client argument error (a TypeError with a code) to SignInFailedError, not AuthUnavailableError", () => {
    const argumentError = Object.assign(new TypeError(`"currentUrl" must be an instance of URL`), {
      code: "ERR_INVALID_ARG_TYPE",
    });

    const mapped = toAuthError(argumentError);

    expect(mapped).toBeInstanceOf(SignInFailedError);
    expect(mapped.message).toBe("Sign-in didn't complete (TypeError, ERR_INVALID_ARG_TYPE)");
  });

  it("maps an unknown Error to SignInFailedError with its name only", () => {
    const mapped = toAuthError(new RangeError(`Unexpected token ${ACCESS_TOKEN}`, { cause: CODE }));

    expect(mapped).toBeInstanceOf(SignInFailedError);
    expect(mapped.message).toBe("Sign-in didn't complete (RangeError)");
    expectNothingCarriedOver(mapped);
  });

  it("maps a thrown non-Error value to SignInFailedError with its type only", () => {
    const mapped = toAuthError(ACCESS_TOKEN);

    expect(mapped).toBeInstanceOf(SignInFailedError);
    expect(mapped.message).toBe("Sign-in didn't complete (string)");
    expectNothingCarriedOver(mapped);
  });

  it("returns errors that are already mapped unchanged", () => {
    const unavailable = new AuthUnavailableError();
    const failed = new SignInFailedError("ResponseBodyError");

    expect(toAuthError(unavailable)).toBe(unavailable);
    expect(toAuthError(failed)).toBe(failed);
  });
});
