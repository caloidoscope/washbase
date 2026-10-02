import { createContext, useContext, useEffect, useState, useSyncExternalStore, type ReactNode } from "react";
import { AppState } from "react-native";

import { createAuthSession } from "./create-session";
import type { AuthSession, SessionState } from "./session";

const SessionContext = createContext<AuthSession | null>(null);

/**
 * Provides the session to the screens, starts it once (stored refresh token → renewal → home, or welcome), and renews
 * when the app returns to the foreground. Tests pass their own `session`.
 */
export function AuthProvider({ children, session: provided }: { children: ReactNode; session?: AuthSession }) {
  const [session] = useState(() => provided ?? createAuthSession());

  useEffect(() => {
    void session.start();
  }, [session]);

  useEffect(() => {
    let current = AppState.currentState;
    const subscription = AppState.addEventListener("change", (next) => {
      if (current.match(/inactive|background/) && next === "active") void session.onForeground();
      current = next;
    });
    return () => subscription.remove();
  }, [session]);

  return <SessionContext.Provider value={session}>{children}</SessionContext.Provider>;
}

export function useAuthSession(): AuthSession {
  const session = useContext(SessionContext);
  if (!session) throw new Error("useAuthSession must be used inside <AuthProvider>");
  return session;
}

export function useSessionState(): SessionState {
  const session = useAuthSession();
  return useSyncExternalStore(session.subscribe, session.getState, session.getState);
}
