import { describe, expect, it } from "vitest";
import { formatClockTime, shortId } from "@/lib/format";

describe("shortId", () => {
  it("takes the 8-character prefix of an id", () => {
    expect(shortId("abcdefghijklmnop")).toBe("abcdefgh");
  });

  it("returns shorter ids unchanged", () => {
    expect(shortId("abc")).toBe("abc");
  });
});

describe("formatClockTime", () => {
  it("renders a parseable ISO timestamp as a local clock time", () => {
    const iso = "2026-06-25T14:32:00.000Z";
    expect(formatClockTime(iso)).toBe(new Date(iso).toLocaleTimeString());
  });

  it("falls back to the raw string when the timestamp is unparseable", () => {
    expect(formatClockTime("not-a-date")).toBe("not-a-date");
  });
});
