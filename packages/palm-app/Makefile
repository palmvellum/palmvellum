# Palm-side build. Targets two .prc artifacts:
#   HelloVellum.prc — minimal toolchain sanity check (no UI resources)
#   Vellum.prc      — Capture v1 (3 tab + log list, with .rcp resources)
#
# Build inside the prc-tools-remix Docker image — the host Mac has none of
# these tools natively. See packages/palm-app/README.md for the canonical
# `docker run` command.
#
# m68k-palmos-gcc is GCC 2.95.3 (C89). Declare locals at block start.

CC      = m68k-palmos-gcc
CFLAGS  = -palmos3.5 -Wall

all: HelloVellum.prc Vellum.prc

# ── HelloVellum (toolchain probe) ─────────────────────────────────────
hello.o: src/hello.c
	$(CC) $(CFLAGS) -c src/hello.c -o hello.o

HelloVellum: hello.o
	$(CC) hello.o -o HelloVellum

HelloVellum.def:
	@echo 'application { "HelloVellum" PvV1 }' > HelloVellum.def

HelloVellum.prc: HelloVellum HelloVellum.def
	build-prc HelloVellum.def HelloVellum

# ── Vellum Capture v1 (.rcp + .c) ─────────────────────────────────────
# pilrc emits one .bin per resource. build-prc consumes the linker output
# plus each .bin to produce the final .prc.

vellum.o: src/vellum.c
	$(CC) $(CFLAGS) -c src/vellum.c -o vellum.o

Vellum: vellum.o
	$(CC) vellum.o -o Vellum

Vellum.def:
	@echo 'application { "Vellum" PvV1 }' > Vellum.def

Vellum.rcp.stamp: src/vellum.rcp
	pilrc src/vellum.rcp .
	touch Vellum.rcp.stamp

Vellum.prc: Vellum Vellum.def Vellum.rcp.stamp
	build-prc Vellum.def Vellum *.bin

clean:
	rm -f *.o *.bin *.def *.stamp HelloVellum Vellum HelloVellum.prc Vellum.prc

.PHONY: all clean
