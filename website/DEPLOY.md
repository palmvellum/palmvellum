# Deploying the PalmVellum landing page

The `website/` directory is a self-contained static site. You can
deploy it three ways. Pick whichever fits how you already host
`tatliving.dev`.

## Path A — Subdirectory of tatliving.dev (`tatliving.dev/palmvellum`)

The cleanest URL, single Vercel project, no DNS work.

### A.1 — If `tatliving.dev` is a Next.js / SvelteKit / static site you control

Drop the contents of `website/` into your `public/palmvellum/`
directory (or whatever your framework calls its static asset
folder). Commit and deploy.

```bash
# from this repo
cp -r website/. ~/path/to/tatliving.dev/public/palmvellum/
cd ~/path/to/tatliving.dev
git add public/palmvellum
git commit -m "feat: add /palmvellum landing"
git push
```

After Vercel re-deploys, the site is live at
`https://tatliving.dev/palmvellum/`.

### A.2 — If `tatliving.dev` should proxy to a separate Vercel project

Deploy `website/` as its own Vercel project (call it
`palmvellum-landing`). Get its production URL — typically
`palmvellum-landing.vercel.app`. Then in `tatliving.dev`'s repo
add a rewrite to `vercel.json`:

```json
{
  "rewrites": [
    { "source": "/palmvellum",          "destination": "https://palmvellum-landing.vercel.app" },
    { "source": "/palmvellum/(.*)",      "destination": "https://palmvellum-landing.vercel.app/$1" }
  ]
}
```

Re-deploy `tatliving.dev`. The landing is now reachable at
`https://tatliving.dev/palmvellum`.

## Path B — Subdomain (`palmvellum.tatliving.dev`)

Slightly more setup but cleaner separation.

1. From this repo, deploy:

   ```bash
   cd website
   npx vercel --prod
   ```

2. Note the production URL (e.g. `palmvellum-landing.vercel.app`).
3. In your domain registrar (or Cloudflare), add a CNAME:

   ```
   palmvellum.tatliving.dev   CNAME   cname.vercel-dns.com
   ```

4. In the Vercel dashboard for the project, **Settings → Domains →
   Add → `palmvellum.tatliving.dev`**. Vercel issues an SSL cert
   automatically.

## Path C — GitHub Pages (backup / mirror)

Free permanent mirror at `palmvellum.github.io`.

```bash
# enable Pages from the org's main repo settings; ship the website
# directory as the site root via a Pages action.
```

(`.github/workflows/pages.yml` to be added separately.)

---

## Local preview

```bash
cd website
python3 -m http.server 8000
open http://127.0.0.1:8000
```

Nothing else needed — pure HTML/CSS/JS, no build step.
