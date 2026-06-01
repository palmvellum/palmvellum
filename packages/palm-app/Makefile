APP     = HelloVellum
CREATOR = PvV1
PRC     = $(APP).prc
CC      = m68k-palmos-gcc
CFLAGS  = -palmos3.5 -Wall
# NOTE: -palmos3.5 because SDK 3.1 uses the legacy Pilot.h header.
# When we target real Palm IIIe 3.1-ROM devices (vs IIIxe / m100+), switch to
# Pilot.h + -palmos3.1. See docs/crypto-spec.md notes on cross-target gating.
# NOTE: m68k-palmos-gcc is GCC 2.95.3 (C89). Declare locals at block start.

all: $(PRC)

hello.o: src/hello.c
	$(CC) $(CFLAGS) -c src/hello.c -o hello.o

$(APP): hello.o
	$(CC) hello.o -o $(APP)

$(APP).def:
	@echo 'application { "$(APP)" $(CREATOR) }' > $(APP).def

$(PRC): $(APP) $(APP).def
	build-prc $(APP).def $(APP)

clean:
	rm -f *.o *.def $(APP) $(PRC)

.PHONY: all clean
