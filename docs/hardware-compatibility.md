# Hardware Compatibility

PalmVellum supports exactly **19 devices**: all consumer Palm-OS
handhelds powered by 2 AAA alkaline batteries, manufactured between
1996 and 2003, with zero internal radios.

This list is closed by design. We do not support rechargeable Palms,
ARM-based Palms (OS 5), or any Palm with a radio module installed.

## Supported devices

### Palm Inc.

| Model                  | Year | Palm OS | RAM   | Screen        | Status     |
|------------------------|------|---------|-------|---------------|------------|
| Pilot 1000             | 1996 | 1.0     | 128KB | 160×160 mono  | 🧪 stretch |
| Pilot 5000             | 1996 | 1.0     | 512KB | 160×160 mono  | 🧪 stretch |
| PalmPilot Personal     | 1997 | 2.0     | 512KB | 160×160 mono  | 🧪 stretch |
| PalmPilot Professional | 1997 | 2.0     | 1MB   | 160×160 mono  | 🧪 stretch |
| Palm III               | 1998 | 3.0     | 2MB   | 160×160 mono  | ✅ tier 1   |
| **Palm IIIe**          | 1999 | 3.1     | 2MB   | 160×160 mono  | ⭐ reference |
| Palm IIIx              | 1999 | 3.1     | 4MB   | 160×160 mono  | ✅ tier 1   |
| Palm IIIxe             | 2000 | 3.5     | 8MB   | 160×160 grey  | ✅ tier 1   |
| Palm m100              | 2000 | 3.5     | 2MB   | 160×160 mono  | ✅ tier 1   |
| Palm m105              | 2001 | 3.5     | 8MB   | 160×160 mono  | ✅ tier 1   |
| Palm m125              | 2001 | 4.0     | 8MB   | 160×160 grey  | ✅ tier 1   |
| Palm Zire              | 2002 | 4.1     | 2MB   | 160×160 mono  | ✅ tier 1   |
| Palm Zire 21           | 2003 | 4.1     | 8MB   | 160×160 mono  | ✅ tier 1   |

### Handspring

| Model            | Year | Palm OS | RAM | Screen        | Notes |
|------------------|------|---------|-----|---------------|-------|
| Visor (original) | 1999 | 3.1     | 2MB | 160×160 mono  | ✅ tier 1; Springboard slot ignored |
| Visor Solo       | 2000 | 3.1     | 2MB | 160×160 mono  | ✅ tier 1 |
| Visor Deluxe     | 2000 | 3.1     | 8MB | 160×160 mono  | ✅ tier 1 |
| Visor Platinum   | 2001 | 3.5     | 8MB | 160×160 mono  | ✅ tier 1; DragonBall VZ 33MHz |
| Visor Neo        | 2001 | 3.5     | 8MB | 160×160 mono  | ✅ tier 1 |

### Sony Clié

| Model             | Year | Palm OS | RAM | Screen           | Notes |
|-------------------|------|---------|-----|------------------|-------|
| **Sony PEG-SL10** | 2002 | 4.1     | 8MB | **320×320 grey** | ⭐ tier 2; only AAA hi-res, jog dial, Memory Stick |

## Explicitly NOT supported

We do not support any device with a rechargeable battery, an ARM CPU
(Palm OS 5), or an integrated radio. This includes:

- ❌ Palm V, Vx — rechargeable Li-ion
- ❌ Palm IIIc, m130 — rechargeable + color backlight
- ❌ Palm m500, m505, m515 — rechargeable
- ❌ Palm Zire 31, 71, 72 — rechargeable color
- ❌ Palm Tungsten W, T, T2, T3, T5, E, E2, C — ARM / rechargeable / radio
- ❌ Palm Treo (all) — cellular radio + rechargeable
- ❌ Handspring Visor Pro — rechargeable Li-ion
- ❌ Handspring Visor Prism — rechargeable + color
- ❌ Handspring Visor Edge — rechargeable
- ❌ Handspring Treo (all) — cellular + rechargeable
- ❌ Sony Clié PEG-S300/320/360, SJ20/22/30/33, T-series, N-series,
  NR70/NR70V, NX-series, NZ90, TH55, TG50, UX50 — all rechargeable;
  many color, ARM, or radio-equipped

If you believe a device is missing from either list, open a
[hardware compatibility issue](https://github.com/palmvellum/palmvellum/issues/new?template=hardware-compat.yml).

## Buying guide

### Where to find them in 2026

- **eBay** — global, best for Visor and Palm Inc. units
- **Yahoo Auctions Japan** — best for Sony Clié SL10 (Japanese
  market surplus)
- **鴨寮街 / 先達後街 (Hong Kong)** — irregular but cheap
- **Surplus electronics retailers** — Computer Reset (TX, closed
  2024), other regional surplus — quickly drying up but worth a
  check
- **eStateSale / Craigslist / Yahoo Auctions Yahoo!Japan** — common
  channels; Palm V / Vx are frequent decoys, verify battery type
  before purchase

### What to check before buying

1. **Battery compartment** — must accept 2× AAA. Reject any unit
   with a rechargeable cell or a cradle-charging contact at the base.
2. **Screen condition** — minor scratches OK; any LCD damage (lines,
   bleeding, vinegar smell from rotting polarizer) is a rejection.
3. **Stylus included** — Palm-Inc. stylus is the same across the III
   and m100 families. Sony stylus is unique to each Clié model.
4. **HotSync button** — micro-switch failure is the #1 reason a
   working Palm cannot sync. Have the seller demonstrate, or assume
   you'll replace it.
5. **Cradle included** — significantly increases value. Without one
   you must source separately, often more expensive than the Palm.

### Price expectations (2026, USD)

| Tier            | Range    | Examples                            |
|-----------------|----------|-------------------------------------|
| Best value      | $15–30   | Palm IIIxe, Visor Deluxe, Palm m105 |
| Collector grade | $40–80   | Palm IIIe (boxed), Palm III         |
| Hi-res target   | $40–120  | Sony PEG-SL10                       |
| Heroic mode     | $80–250+ | Pilot 1000, Pilot 5000, working     |

### Required accessories

- **HotSync cradle** matching your model
- **USB-Serial adapter with genuine FTDI FT232R chip** — counterfeits
  and CH340/PL2303 clones cause endless macOS pain
- **Fresh alkaline AAA batteries** — never use NiMH for cold storage;
  alkaline shelf-life beats NiMH self-discharge
