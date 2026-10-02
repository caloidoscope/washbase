import { Pressable, StyleSheet } from "react-native";

import { ThemedText } from "@/components/themed-text";
import { Spacing } from "@/constants/theme";
import { useTheme } from "@/hooks/use-theme";

type ButtonProps = {
  title: string;
  onPress: () => void;
  accessibilityLabel?: string;
  accessibilityHint?: string;
  disabled?: boolean;
  variant?: "primary" | "secondary";
};

export function Button({
  title,
  onPress,
  accessibilityLabel,
  accessibilityHint,
  disabled = false,
  variant = "primary",
}: ButtonProps) {
  const theme = useTheme();
  const primary = variant === "primary";
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={accessibilityLabel ?? title}
      accessibilityHint={accessibilityHint}
      accessibilityState={{ disabled }}
      disabled={disabled}
      onPress={onPress}
      style={({ pressed }) => [
        styles.button,
        { backgroundColor: primary ? "#208AEF" : theme.backgroundElement },
        (pressed || disabled) && styles.dimmed,
      ]}>
      <ThemedText style={[styles.label, primary && styles.primaryLabel]}>{title}</ThemedText>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  button: {
    minHeight: 48,
    minWidth: 160,
    paddingHorizontal: Spacing.four,
    paddingVertical: Spacing.three,
    borderRadius: Spacing.three,
    alignItems: "center",
    justifyContent: "center",
  },
  label: {
    fontWeight: 600,
  },
  primaryLabel: {
    color: "#ffffff",
  },
  dimmed: {
    opacity: 0.6,
  },
});
