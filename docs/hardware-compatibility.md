# Hardware Compatibility

PalmVellum's reference target and design focus is the **AAA-battery
Palm family** - consumer Palm OS handhelds powered by 2 AAA alkaline
batteries, manufactured between 1996 and 2003, with no integrated
radio. That is the device we believe in, the device the UI was tuned
for, and the device every screenshot in the docs comes from.

That said, the platform itself just speaks standard HotSync. There is
no DRM and no device check. **Other Palm OS handhelds - rechargeable
Palms, Treos, Tungstens, Sony Cliés outside the AAA line - are not our
target audience, but they are welcome to use the platform.** If a
HotSync conduit lands on a Palm V or a Tungsten T3, the records sync
the same way and the AI features work the same way. We just will not
be testing those devices ourselves, and the UI assumptions (160x160
mono screen, Graffiti input) won't always fit.

## Reference target - AAA-battery family

The devices we test against and design for.

### Palm Inc.

| Model                  | Year | Palm OS | RAM   | Screen        | Status     |
|------------------------|------|---------|-------|---------------|------------|
| Pilot 1000             | 1996 | 1.0     | 128KB | 160x160 mono  | stretch    |
| Pilot 5000             | 1996 | 1.0     | 512KB | 160x160 mono  | stretch    |
| PalmPilot Personal     | 1997 | 2.0     | 512KB | 160x160 mono  | stretch    |
| PalmPilot Professional | 1997 | 2.0     | 1MB   | 160x160 mono  | stretch    |
| Palm III               | 1998 | 3.0     | 2MB   | 160x160 mono  | tier 1     |
| **Palm IIIe**          | 1999 | 3.1     | 2MB   | 160x160 mono  | reference  |
| Palm IIIx              | 1999 | 3.1     | 4MB   | 160x160 mono  | tier 1     |
| Palm IIIxe             | 2000 | 3.5     | 8MB   | 160x160 grey  | tier 1     |
| Palm m100              | 2000 | 3.5     | 2MB   | 160x160 mono  | tier 1     |
| Palm m105              | 2001 | 3.5     | 8MB   | 160x160 mono  | tier 1     |
| Palm m125              | 2001 | 4.0     | 8MB   | 160x160 grey  | tier 1     |
| Palm Zire              | 2002 | 4.1     | 2MB   | 160x160 mono  | tier 1     |
| Palm Zire 21           | 2003 | 4.1     | 8MB   | 160x160 mono  | tier 1     |

### Handspring

| Model            | Year | Palm OS | RAM | Screen        | Notes |
|------------------|------|---------|-----|---------------|-------|
| Visor (original) | 1999 | 3.1     | 2MB | 160x160 mono  | tier 1; Springboard slot ignored |
| Visor Solo       | 2000 | 3.1     | 2MB | 160x160 mono  | tier 1 |
| Visor Deluxe     | 2000 | 3.1     | 8MB | 160x160 mono  | tier 1 |
| Visor Platinum   | 2001 | 3.5     | 8MB | 160x160 mono  | tier 1; DragonBall VZ 33MHz |
| Visor Neo        | 2001 | 3.5     | 8MB | 160x160 mono  | tier 1 |

### Sony Clié

| Model             | Year | Palm OS | RAM | Screen           | Notes |
|-------------------|------|---------|-----|------------------|-------|
| **Sony PEG-SL10** | 2002 | 4.1     | 8MB | **320x320 grey** | tier 2; only AAA hi-res, jog dial, Memory Stick |

## Other Palm OS devices

Devices outside the AAA family - rechargeable Palms (V, Vx, m500-series,
Zire 31/71/72), ARM-based Palms (Tungsten T/T2/T3/T5/E/E2/C, Zire 72),
the Treo line, Handspring Visor Pro / Prism / Edge - are **not** part
of our test matrix, and we do not promise the UI will fit their screens
or that HotSync conduits compiled against Palm OS 3.1 will work on
Palm OS 5 ARM.

Even so, they are physically capable of running the conduits, and the
backend has no device check. If you make it work on your Tungsten T3,
that is great. We just will not help debug it and we may make changes
that break it without warning.

## Why focus on the AAA-battery family

Three reasons:

1. **They still work today** with parts you already have. Two AA-sized
   alkaline cells from any corner shop run a Palm IIIe for months.
   No proprietary battery, no charger that dies in five years.
2. **No integrated radio.** The device cannot, on its own, leak
   anything. What you sync to the cloud is your choice, not a
   background process.
3. **The form factor and the constraints are the product.** 160x160,
   monochrome, Graffiti, no notifications - that is the low-fi
   experience the platform is designed around.

## Buying tips

If you are looking to pick up a device for PalmVellum, here is what to
check before paying:

1. **Battery compartment** - must accept 2x AAA. Reject any unit with
   visible alkaline leak corrosion on the terminals.
2. **Screen** - power on, hold reset, look for dead columns or rows.
3. **Digitizer** - calibrate (Prefs -> Digitizer) and verify all four
   corners are reachable without forcing.
4. **HotSync** - bring a cradle if possible. Without a cradle a serial
   adapter is fine but you cannot test it on the spot.

A **fresh set of alkaline AAA batteries** is essential - never use NiMH
rechargeables for long-term storage; they self-discharge and leak.
