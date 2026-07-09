import type { Metadata } from "next";
import { Roboto } from "next/font/google";
import "./globals.css";

// Roboto — the Material 3 default typeface (animation-and-ui-guidelines mandate
// M3 fonts). Latin subset, the weights the type scale uses, swap to avoid FOIT.
const roboto = Roboto({
  subsets: ["latin"],
  weight: ["400", "500"],
  display: "swap",
  variable: "--font-roboto",
});

export const metadata: Metadata = {
  title: "Living Spam Classifier Lab — Living Anti-Spam System",
  description:
    "A live, three-pane spam-classifier lab over the Living Anti-Spam System: decisions stream in as the pipeline makes them.",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en" className={roboto.variable}>
      <head>
        {/* Material Symbols (Outlined) — the one icon set used across the product. */}
        <link
          rel="stylesheet"
          href="https://fonts.googleapis.com/css2?family=Material+Symbols+Outlined:opsz,wght,FILL,GRAD@24,400,0..1,0&display=swap"
        />
      </head>
      <body className="font-sans antialiased">{children}</body>
    </html>
  );
}
