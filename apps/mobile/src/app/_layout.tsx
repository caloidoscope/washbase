import { DarkTheme, DefaultTheme, Stack, ThemeProvider } from 'expo-router';
import * as SplashScreen from 'expo-splash-screen';
import * as WebBrowser from 'expo-web-browser';
import { useEffect } from 'react';
import { useColorScheme } from 'react-native';

import { AuthProvider, useSessionState } from '@/lib/auth/auth-provider';

// Closes the sign-in popup on web; a no-op on iOS and Android.
WebBrowser.maybeCompleteAuthSession();
void SplashScreen.preventAutoHideAsync();

/** Keeps the splash screen up until the stored session has been checked. */
function HideSplashWhenReady() {
  const state = useSessionState();
  useEffect(() => {
    if (state.status !== 'loading') void SplashScreen.hideAsync();
  }, [state.status]);
  return null;
}

export default function RootLayout() {
  const colorScheme = useColorScheme();
  return (
    <ThemeProvider value={colorScheme === 'dark' ? DarkTheme : DefaultTheme}>
      <AuthProvider>
        <HideSplashWhenReady />
        <Stack screenOptions={{ headerShown: false }} />
      </AuthProvider>
    </ThemeProvider>
  );
}
