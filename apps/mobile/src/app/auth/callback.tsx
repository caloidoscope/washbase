import { Redirect } from 'expo-router';
import * as WebBrowser from 'expo-web-browser';

// The authorization server redirects here (washbase://auth/callback, or exp://…/--/auth/callback in Expo Go).
// expo-auth-session reads the code from the in-app browser's result; on Android the deep link also opens this
// route, so it hands the result to the waiting prompt (on web) and goes back to the session screen, which shows the
// "Signing in" state while the code is exchanged.
WebBrowser.maybeCompleteAuthSession();

export default function AuthCallbackScreen() {
  return <Redirect href="/" />;
}
