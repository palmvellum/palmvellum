# `packages/shared-schema`

Single source of truth for the data model:

- TypeScript types (consumed by `pwa` and Go via codegen)
- Zod validators
- Supabase SQL migrations

## Layout

```
shared-schema/
├── src/
│   └── types.ts
├── migrations/
│   └── 0001_init.sql
└── package.json
```

## Status

🚧 Scaffold pending.
