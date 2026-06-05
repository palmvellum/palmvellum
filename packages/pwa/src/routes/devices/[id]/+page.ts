// /devices/[id] is a dynamic route — adapter-static can't prerender
// without knowing every Palm's ULID, so we opt out and let the SPA
// fallback (index.html) hydrate it on the client.
export const prerender = false;
export const ssr = false;
