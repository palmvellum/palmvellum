// Package vellum encodes and decodes the on-Palm VellumDB record
// format defined by packages/palm-app/src/vellum.c.
//
//	Offset  Length  Field
//	------  ------  ----------------------------------
//	0       1       version (currently 0x01)
//	1       1       type  — 1=AI / 2=thought / 3=todo
//	2       1       status — 0=draft 1=synced 2=answered 3=done
//	3       1       reserved (0)
//	4       4       ctime (UInt32, Palm epoch — seconds since 1904)
//	8       2       bodyLen
//	10      2       ansLen
//	12      bodyLen body bytes
//	..      ansLen  answer bytes
package vellum

import (
	"encoding/binary"
	"fmt"
)

const HdrLen = 12

type Type uint8

const (
	TypeAI      Type = 1
	TypeThought Type = 2
	TypeTodo    Type = 3
)

type Status uint8

const (
	StatusDraft    Status = 0
	StatusSynced   Status = 1
	StatusAnswered Status = 2
	StatusDone     Status = 3
)

func (t Type) Cloud() string {
	switch t {
	case TypeAI:
		return "aiquery"
	case TypeThought:
		return "thought"
	case TypeTodo:
		return "todo"
	default:
		return ""
	}
}

func TypeFromCloud(s string) Type {
	switch s {
	case "aiquery":
		return TypeAI
	case "thought":
		return TypeThought
	case "todo":
		return TypeTodo
	default:
		return 0
	}
}

// Record is one VellumDB chunk decoded into Go-native fields.
type Record struct {
	Version   uint8
	Type      Type
	Status    Status
	CtimePalm uint32 // Palm epoch seconds
	Body      string
	Answer    string
}

func Decode(b []byte) (Record, error) {
	if len(b) < HdrLen {
		return Record{}, fmt.Errorf("vellum: short record: %d bytes", len(b))
	}
	r := Record{
		Version:   b[0],
		Type:      Type(b[1]),
		Status:    Status(b[2]),
		CtimePalm: binary.BigEndian.Uint32(b[4:8]),
	}
	bodyLen := int(binary.BigEndian.Uint16(b[8:10]))
	ansLen := int(binary.BigEndian.Uint16(b[10:12]))
	if HdrLen+bodyLen+ansLen > len(b) {
		return Record{}, fmt.Errorf(
			"vellum: declared bodyLen=%d ansLen=%d overruns chunk of %d",
			bodyLen, ansLen, len(b))
	}
	r.Body = string(b[HdrLen : HdrLen+bodyLen])
	r.Answer = string(b[HdrLen+bodyLen : HdrLen+bodyLen+ansLen])
	return r, nil
}

func (r Record) Encode() []byte {
	bodyBytes := []byte(r.Body)
	ansBytes := []byte(r.Answer)
	out := make([]byte, HdrLen+len(bodyBytes)+len(ansBytes))
	out[0] = 1
	out[1] = byte(r.Type)
	out[2] = byte(r.Status)
	out[3] = 0
	binary.BigEndian.PutUint32(out[4:8], r.CtimePalm)
	binary.BigEndian.PutUint16(out[8:10], uint16(len(bodyBytes)))
	binary.BigEndian.PutUint16(out[10:12], uint16(len(ansBytes)))
	copy(out[HdrLen:HdrLen+len(bodyBytes)], bodyBytes)
	copy(out[HdrLen+len(bodyBytes):], ansBytes)
	return out
}
