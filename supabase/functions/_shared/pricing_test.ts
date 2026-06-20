// The real markup is a commercial secret supplied via the PRICE_MARKUP
// function secret, so these tests set a dummy value and assert the math
// scales by it — no real multiplier appears in the repo.
const TEST_MARKUP = 2;
Deno.env.set("PRICE_MARKUP", String(TEST_MARKUP));

import { assertEquals } from "jsr:@std/assert";
import { costMicroUsd, isKnownModel, MARKUP, usdToMicro, microToUsd, MIN_TOPUP_USD } from "./pricing.ts";

Deno.test("cost applies the configured markup (gpt-4o-mini)", () => {
  // 1000 in @ $0.15/1M + 500 out @ $0.60/1M = 150 + 300 = 450 micro-USD raw.
  assertEquals(costMicroUsd("gpt-4o-mini", 1000, 500), 450 * TEST_MARKUP);
});

Deno.test("cost applies the configured markup (gpt-4o)", () => {
  // 1000*2.5 + 1000*10 = 12500 micro-USD raw.
  assertEquals(costMicroUsd("gpt-4o", 1000, 1000), 12500 * TEST_MARKUP);
});

Deno.test("MARKUP comes from the PRICE_MARKUP secret", () => {
  assertEquals(MARKUP, TEST_MARKUP);
});

Deno.test("unknown model falls back to gpt-4o-mini (still charges, never free)", () => {
  // fallback = gpt-4o-mini: (1000*0.15 + 1000*0.60) = 750 micro-USD raw.
  assertEquals(costMicroUsd("some-future-model", 1000, 1000), 750 * TEST_MARKUP);
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
