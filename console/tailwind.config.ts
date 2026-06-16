import type { Config } from "tailwindcss";

/**
 * Material Design 3 aligned theme. Colours are CSS custom properties (defined in
 * globals.css) so the M3 tonal roles and the four decision-tier colours live in
 * one place and can be themed. The type scale below mirrors the M3 roles used by
 * the analyzer — we never invent sizes outside it (see animation-and-ui-guidelines).
 */
const config: Config = {
  content: ["./app/**/*.{ts,tsx}", "./components/**/*.{ts,tsx}", "./lib/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        surface: "rgb(var(--m3-surface) / <alpha-value>)",
        "surface-container": "rgb(var(--m3-surface-container) / <alpha-value>)",
        "surface-variant": "rgb(var(--m3-surface-variant) / <alpha-value>)",
        "on-surface": "rgb(var(--m3-on-surface) / <alpha-value>)",
        "on-surface-variant": "rgb(var(--m3-on-surface-variant) / <alpha-value>)",
        outline: "rgb(var(--m3-outline) / <alpha-value>)",
        primary: "rgb(var(--m3-primary) / <alpha-value>)",
        "on-primary": "rgb(var(--m3-on-primary) / <alpha-value>)",
        // Decision tiers — each a distinct hue (allow→block) with a soft container.
        "tier-allow": "rgb(var(--tier-allow) / <alpha-value>)",
        "tier-allow-container": "rgb(var(--tier-allow-container) / <alpha-value>)",
        "tier-warn": "rgb(var(--tier-warn) / <alpha-value>)",
        "tier-warn-container": "rgb(var(--tier-warn-container) / <alpha-value>)",
        "tier-quarantine": "rgb(var(--tier-quarantine) / <alpha-value>)",
        "tier-quarantine-container": "rgb(var(--tier-quarantine-container) / <alpha-value>)",
        "tier-block": "rgb(var(--tier-block) / <alpha-value>)",
        "tier-block-container": "rgb(var(--tier-block-container) / <alpha-value>)",
      },
      fontFamily: {
        sans: ["var(--font-roboto)", "system-ui", "sans-serif"],
      },
      // M3 type scale (the roles the analyzer uses). [size, {lineHeight, letterSpacing, weight}].
      fontSize: {
        "display-sm": ["2.25rem", { lineHeight: "2.75rem", letterSpacing: "0", fontWeight: "400" }],
        "headline-sm": ["1.5rem", { lineHeight: "2rem", letterSpacing: "0", fontWeight: "400" }],
        "title-lg": ["1.375rem", { lineHeight: "1.75rem", letterSpacing: "0", fontWeight: "500" }],
        "title-md": ["1rem", { lineHeight: "1.5rem", letterSpacing: "0.009375em", fontWeight: "500" }],
        "title-sm": ["0.875rem", { lineHeight: "1.25rem", letterSpacing: "0.00625em", fontWeight: "500" }],
        "body-lg": ["1rem", { lineHeight: "1.5rem", letterSpacing: "0.03125em", fontWeight: "400" }],
        "body-md": ["0.875rem", { lineHeight: "1.25rem", letterSpacing: "0.015625em", fontWeight: "400" }],
        "label-lg": ["0.875rem", { lineHeight: "1.25rem", letterSpacing: "0.00625em", fontWeight: "500" }],
        "label-md": ["0.75rem", { lineHeight: "1rem", letterSpacing: "0.03125em", fontWeight: "500" }],
      },
      borderRadius: {
        // M3 shape tokens.
        sm: "0.5rem",
        md: "0.75rem",
        lg: "1rem",
        xl: "1.75rem",
      },
      keyframes: {
        "pulse-soft": {
          "0%, 100%": { opacity: "1" },
          "50%": { opacity: "0.72" },
        },
      },
      animation: {
        // Slow pulse = "I'm working" (animation guidelines: 500ms–1s indeterminate).
        "pulse-soft": "pulse-soft 900ms ease-in-out infinite",
      },
    },
  },
  plugins: [],
};

export default config;
