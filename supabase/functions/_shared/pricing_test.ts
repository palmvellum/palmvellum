import { assertEquals } from "jsr:@std/assert";
import { costMicroUsd, isKnownModel, MARKUP, usdToMicro, microToUsd, MIN_TOPUP_USD } from "./pricing.ts";

Deno.test("gpt-4o-mini cost includes 15x retail markup", () => {
  // 1000 in @ $0.15/1M + 500 out @ $0.60/1M = 150 + 300 = 450 micro-USD raw.
  // ×15 markup = 6750 micro-USD.
  assertEquals(costMicroUsd("gpt-4o-mini", 1000, 500), 6750);
});

Deno.test("gpt-4o cost", () => {
  // 1000*2.5 + 1000*10 = 12500 raw; ×15 = 187500 micro-USD.
  assertEquals(costMicroUsd("gpt-4o", 1000, 1000), 187500);
});

Deno.test("markup constant is 15x", () => {
  assertEquals(MARKUP, 15);
});

Deno.test("unknown model falls back (still charges, never free)", () => {
  const c = costMicroUsd("some-future-model", 1000, 1000);
  // fallback = gpt-4o-mini: (1000*0.15 + 1000*0.60)*15 = 750*15 = 11250
  assertEquals(c, 11250);
  assertEquals(isKnownModel("some-future-model"), false);
  assertEquals(isKnownModel("gpt-4o-mini"), true);
});

Deno.test("zero usage costs zero", () => {
  assertEquals(costMicroUsd("gpt-4o", 0, 0), 0);
});

Deno.test("USD <-> micro conversions + minimum", () => {
  assertEquals(usdToMicro(10), 10_000_000);
  assertEquals(microToUsd(10_000_000), 10);
  assertEquals(MIN_TOPUP_USD, 10);
});
