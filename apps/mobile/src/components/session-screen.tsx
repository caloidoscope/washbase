import { useState } from "react";
import { ActivityIndicator, StyleSheet } from "react-native";
import { SafeAreaView } from "react-native-safe-area-context";

import { Button } from "@/components/button";
import { ThemedText } from "@/components/themed-text";
import { ThemedView } from "@/components/themed-view";
import { MaxContentWidth, Spacing } from "@/constants/theme";
import { useAuthSession, useSessionState } from "@/lib/auth/auth-provider";

export const UNREACHABLE_MESSAGE = "Can't reach Washbase right now. Try again.";
export const SIGN_IN_FAILED_MESSAGE = "Sign-in didn't complete. Try again.";

/** The app's one screen, by session state: loading, welcome, signing in, home, or can't reach Washbase. */
export function SessionScreen() {
  const state = useSessionState();
  return (
    <ThemedView style={styles.container}>
      <SafeAreaView style={styles.content}>
        {state.status === "loading" && <Busy label="Loading" />}
        {state.status === "signing-in" && <Busy label="Signing in" />}
        {state.status === "signed-out" && <Welcome failed={state.notice === "sign-in-failed"} />}
        {state.status === "signed-in" && <Home text={`Signed in as ${state.user.name} (${state.user.roleLabel})`} />}
        {state.status === "unreachable" && <Unreachable />}
      </SafeAreaView>
    </ThemedView>
  );
}

function Busy({ label }: { label: string }) {
  return (
    <ThemedView style={styles.section} accessible accessibilityLabel={label} accessibilityRole="progressbar">
      <ActivityIndicator size="large" />
      <ThemedText themeColor="textSecondary">{label}…</ThemedText>
    </ThemedView>
  );
}

function Welcome({ failed }: { failed: boolean }) {
  const session = useAuthSession();
  const [opening, setOpening] = useState(false);
  const signIn = async () => {
    setOpening(true);
    try {
      await session.signIn();
    } finally {
      setOpening(false);
    }
  };
  return (
    <ThemedView style={styles.section}>
      <ThemedText type="subtitle" accessibilityRole="header">
        Washbase
      </ThemedText>
      {failed && (
        <ThemedText accessibilityRole="alert" style={styles.center}>
          {SIGN_IN_FAILED_MESSAGE}
        </ThemedText>
      )}
      <Button
        title="Sign in"
        accessibilityHint="Opens the Washbase sign-in page"
        disabled={opening}
        onPress={() => void signIn()}
      />
    </ThemedView>
  );
}

function Home({ text }: { text: string }) {
  const session = useAuthSession();
  const [signingOut, setSigningOut] = useState(false);
  const signOut = async () => {
    setSigningOut(true);
    try {
      await session.signOut();
    } finally {
      setSigningOut(false);
    }
  };
  return (
    <ThemedView style={styles.section}>
      <ThemedText type="subtitle" accessibilityRole="header">
        Washbase
      </ThemedText>
      <ThemedText style={styles.center}>{text}</ThemedText>
      <Button title="Sign out" variant="secondary" disabled={signingOut} onPress={() => void signOut()} />
    </ThemedView>
  );
}

function Unreachable() {
  const session = useAuthSession();
  return (
    <ThemedView style={styles.section}>
      <ThemedText accessibilityRole="alert" style={styles.center}>
        {UNREACHABLE_MESSAGE}
      </ThemedText>
      <Button
        title="Try again"
        accessibilityHint="Tries to reach Washbase again"
        onPress={() => void session.retry()}
      />
    </ThemedView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    flexDirection: "row",
    justifyContent: "center",
  },
  content: {
    flex: 1,
    maxWidth: MaxContentWidth,
    paddingHorizontal: Spacing.four,
    justifyContent: "center",
  },
  section: {
    alignItems: "center",
    gap: Spacing.four,
  },
  center: {
    textAlign: "center",
  },
});
